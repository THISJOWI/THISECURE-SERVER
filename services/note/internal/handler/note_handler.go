package handler

import (
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/thisuite/thisecure/note/internal/model"
	"github.com/thisuite/thisecure/note/internal/service"
	"github.com/thisuite/thisecure/pkg/middleware"
)

type NoteHandler struct {
	svc *service.NoteService
}

func NewNoteHandler(svc *service.NoteService) *NoteHandler {
	return &NoteHandler{svc: svc}
}

func (h *NoteHandler) Register(r *gin.RouterGroup) {
	r.POST("", h.Create)
	r.POST("/import", h.Import)
	r.GET("", h.GetAll)
	r.GET("/search", h.Search)
	r.GET("/:title", h.GetByTitle)
	r.GET("/id/:id", h.GetByID)
	r.PUT("/:id", h.Update)
	r.DELETE("/:id", h.Delete)
}

func (h *NoteHandler) error(c *gin.Context, status int, err error) {
	if strings.Contains(err.Error(), "not found") {
		c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
		return
	}
	c.JSON(status, gin.H{"error": "internal server error"})
}

func (h *NoteHandler) Create(c *gin.Context) {
	var req model.NoteRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	userID := middleware.GetUserID(c)
	note, err := h.svc.Create(c.Request.Context(), req, userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, note)
}

func (h *NoteHandler) Import(c *gin.Context) {
	var reqs []model.NoteRequest
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

func (h *NoteHandler) GetAll(c *gin.Context) {
	userID := middleware.GetUserID(c)
	notes, err := h.svc.GetAll(c.Request.Context(), userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	if notes == nil {
		notes = []model.Note{}
	}
	c.JSON(http.StatusOK, notes)
}

func (h *NoteHandler) Search(c *gin.Context) {
	title := c.Query("title")
	userID := middleware.GetUserID(c)
	notes, err := h.svc.SearchByTitle(c.Request.Context(), title, userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	if notes == nil {
		notes = []model.Note{}
	}
	c.JSON(http.StatusOK, notes)
}

func (h *NoteHandler) GetByTitle(c *gin.Context) {
	title := c.Param("title")
	userID := middleware.GetUserID(c)
	note, err := h.svc.GetByTitle(c.Request.Context(), title, userID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if note == nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
		return
	}
	c.JSON(http.StatusOK, note)
}

func (h *NoteHandler) GetByID(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid id"})
		return
	}
	userID := middleware.GetUserID(c)
	note, err := h.svc.GetByID(c.Request.Context(), id, userID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if note == nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
		return
	}
	c.JSON(http.StatusOK, note)
}

func (h *NoteHandler) Update(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid id"})
		return
	}
	var req model.NoteRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	userID := middleware.GetUserID(c)
	note, err := h.svc.Update(c.Request.Context(), id, req, userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, note)
}

func (h *NoteHandler) Delete(c *gin.Context) {
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
