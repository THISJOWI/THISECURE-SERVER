package handler

import (
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/thisuite/thisecure/password/internal/model"
	"github.com/thisuite/thisecure/password/internal/service"
	"github.com/thisuite/thisecure/pkg/middleware"
)

type PasswordHandler struct {
	svc  *service.PasswordService
	dedup *service.DedupService
}

func NewPasswordHandler(svc *service.PasswordService, dedup *service.DedupService) *PasswordHandler {
	return &PasswordHandler{svc: svc, dedup: dedup}
}

func (h *PasswordHandler) Register(r *gin.RouterGroup) {
	r.GET("", h.GetAll)
	r.GET("/:id", h.GetByID)
	r.POST("", h.Create)
	r.PUT("/:id", h.Update)
	r.DELETE("/:id", h.Delete)
	r.POST("/import", h.Import)
	r.POST("/admin/analyze-duplicates", h.AnalyzeDuplicates)
	r.POST("/admin/remove-duplicates", h.RemoveDuplicates)
}

func (h *PasswordHandler) error(c *gin.Context, status int, err error) {
	if strings.Contains(err.Error(), "not found") {
		c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
		return
	}
	c.JSON(status, gin.H{"error": "internal server error"})
}

func (h *PasswordHandler) GetAll(c *gin.Context) {
	userID := middleware.GetUserID(c)
	pws, err := h.svc.GetAll(c.Request.Context(), userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	if pws == nil {
		pws = []model.Password{}
	}
	c.JSON(http.StatusOK, pws)
}

func (h *PasswordHandler) GetByID(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid id"})
		return
	}
	userID := middleware.GetUserID(c)
	pw, err := h.svc.GetByID(c.Request.Context(), id, userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	if pw == nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
		return
	}
	c.JSON(http.StatusOK, pw)
}

func (h *PasswordHandler) Create(c *gin.Context) {
	var req model.PasswordRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	userID := middleware.GetUserID(c)
	pw, err := h.svc.Create(c.Request.Context(), req, userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, pw)
}

func (h *PasswordHandler) Update(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid id"})
		return
	}
	var req model.PasswordRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	userID := middleware.GetUserID(c)
	pw, err := h.svc.Update(c.Request.Context(), id, req, userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, pw)
}

func (h *PasswordHandler) Delete(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid id"})
		return
	}
	userID := middleware.GetUserID(c)
	if err := h.svc.Delete(c.Request.Context(), id, userID); err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "deleted"})
}

func (h *PasswordHandler) Import(c *gin.Context) {
	var reqs []model.PasswordRequest
	if err := c.ShouldBindJSON(&reqs); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	userID := middleware.GetUserID(c)
	result, err := h.svc.Import(c.Request.Context(), reqs, userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, result)
}

func (h *PasswordHandler) AnalyzeDuplicates(c *gin.Context) {
	userID := middleware.GetUserID(c)
	analysis, err := h.dedup.AnalyzeDuplicates(c.Request.Context(), userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, analysis)
}

func (h *PasswordHandler) RemoveDuplicates(c *gin.Context) {
	userID := middleware.GetUserID(c)
	removed, err := h.dedup.RemoveDuplicates(c.Request.Context(), userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"removed": removed})
}
