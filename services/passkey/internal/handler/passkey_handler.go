package handler

import (
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/thisuite/thisecure/passkey/internal/model"
	"github.com/thisuite/thisecure/passkey/internal/service"
	"github.com/thisuite/thisecure/pkg/middleware"
)

type PasskeyHandler struct {
	svc *service.PasskeyService
}

func NewPasskeyHandler(svc *service.PasskeyService) *PasskeyHandler {
	return &PasskeyHandler{svc: svc}
}

func (h *PasskeyHandler) Register(r *gin.RouterGroup) {
	r.GET("", h.GetAll)
	r.GET("/:id", h.GetByID)
	r.POST("", h.Create)
	r.PUT("/:id", h.Update)
	r.DELETE("/:id", h.Delete)
}

func (h *PasskeyHandler) error(c *gin.Context, status int, err error) {
	if strings.Contains(err.Error(), "not found") {
		c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
		return
	}
	c.JSON(status, gin.H{"error": "internal server error"})
}

func (h *PasskeyHandler) GetAll(c *gin.Context) {
	userID := middleware.GetUserID(c)
	pks, err := h.svc.GetAll(c.Request.Context(), userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	if pks == nil {
		pks = []model.Passkey{}
	}
	c.JSON(http.StatusOK, pks)
}

func (h *PasskeyHandler) GetByID(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid id"})
		return
	}
	userID := middleware.GetUserID(c)
	pk, err := h.svc.GetByID(c.Request.Context(), id, userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	if pk == nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
		return
	}
	c.JSON(http.StatusOK, pk)
}

func (h *PasskeyHandler) Create(c *gin.Context) {
	var req model.PasskeyRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	userID := middleware.GetUserID(c)
	pk, err := h.svc.Create(c.Request.Context(), req, userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, pk)
}

func (h *PasskeyHandler) Update(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid id"})
		return
	}
	var req model.PasskeyRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	userID := middleware.GetUserID(c)
	pk, err := h.svc.Update(c.Request.Context(), id, req, userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, pk)
}

func (h *PasskeyHandler) Delete(c *gin.Context) {
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
