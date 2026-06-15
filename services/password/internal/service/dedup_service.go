package service

import (
	"context"
	"fmt"
	"log"
	"sort"

	"github.com/thisuite/thisecure/password/internal/model"
	"github.com/thisuite/thisecure/password/internal/repository"
	"github.com/thisuite/thisecure/pkg/crypto"
)

type DedupService struct {
	repo   *repository.PasswordRepo
	encKey []byte
}

func NewDedupService(repo *repository.PasswordRepo, encKey []byte) *DedupService {
	return &DedupService{repo: repo, encKey: encKey}
}

func (s *DedupService) AnalyzeDuplicates(ctx context.Context, userID string) (*model.DuplicateAnalysis, error) {
	pws, err := s.repo.FindByUserID(ctx, userID)
	if err != nil {
		return nil, err
	}

	groups := make(map[string]*model.DuplicateGroup)
	order := []string{}

	for _, pw := range pws {
		key := fmt.Sprintf("%s::%s::%s", pw.Name, pw.Website, pw.Username)
		if g, ok := groups[key]; ok {
			g.Count++
			g.IDs = append(g.IDs, pw.ID)
		} else {
			order = append(order, key)
			groups[key] = &model.DuplicateGroup{
				Name:     pw.Name,
				Website:  pw.Website,
				Username: pw.Username,
				Count:    1,
				IDs:      []int64{pw.ID},
			}
		}
	}

	result := &model.DuplicateAnalysis{}
	for _, key := range order {
		if g := groups[key]; g.Count > 1 {
			result.Groups = append(result.Groups, *g)
			result.Total += g.Count - 1
		}
	}
	if result.Groups == nil {
		result.Groups = []model.DuplicateGroup{}
	}
	return result, nil
}

func (s *DedupService) RemoveDuplicates(ctx context.Context, userID string) (int, error) {
	analysis, err := s.AnalyzeDuplicates(ctx, userID)
	if err != nil {
		return 0, err
	}

	removed := 0
	for _, group := range analysis.Groups {
		sort.Slice(group.IDs, func(i, j int) bool {
			return group.IDs[i] > group.IDs[j]
		})
		kept, err := s.repo.FindByID(ctx, group.IDs[0])
		if err != nil || kept == nil {
			continue
		}
		s.decrypt(kept)

		for _, id := range group.IDs[1:] {
			pw, err := s.repo.FindByID(ctx, id)
			if err != nil || pw == nil {
				continue
			}
			s.decrypt(pw)
			if pw.Password != kept.Password {
				log.Printf("WARN: skipping duplicate %d: password content differs from %d", id, group.IDs[0])
				continue
			}
			if err := s.repo.Delete(ctx, id, userID); err == nil {
				removed++
			}
		}
	}
	return removed, nil
}

func (s *DedupService) decrypt(pw *model.Password) {
	if len(s.encKey) == 0 || pw.Password == "" {
		return
	}
	dec, err := crypto.Decrypt(pw.Password, s.encKey)
	if err != nil {
		pw.Password = ""
		return
	}
	pw.Password = string(dec)
}
