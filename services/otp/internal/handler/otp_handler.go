package handler

import (
	"log"
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/thisuite/thisecure/otp/internal/model"
	"github.com/thisuite/thisecure/otp/internal/service"
	"github.com/thisuite/thisecure/pkg/middleware"
)

func sanitizeLog(s string) string {
	return strings.NewReplacer("\n", "", "\r", "").Replace(s)
}

type OtpHandler struct {
	svc  *service.OtpService
	qr   *service.QrService
}

func NewOtpHandler(svc *service.OtpService, qr *service.QrService) *OtpHandler {
	return &OtpHandler{svc: svc, qr: qr}
}

func (h *OtpHandler) Register(r *gin.RouterGroup) {
	r.POST("/decode-qr", h.DecodeQR)
	r.GET("", h.GetAll)
	r.GET("/:id", h.GetByID)
	r.POST("", h.Create)
	r.PUT("/:id", h.Update)
	r.DELETE("/:id", h.Delete)
	r.POST("/:id/validate", h.Validate)
}

func (h *OtpHandler) error(c *gin.Context, status int, err error) {
	if strings.Contains(err.Error(), "not found") {
		c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
		return
	}
	log.Printf("ERROR: %s %s: %v", c.Request.Method, sanitizeLog(c.Request.URL.Path), err)
	c.JSON(status, gin.H{"error": "internal server error"})
}

func (h *OtpHandler) DecodeQR(c *gin.Context) {
	var req struct {
		Image string `json:"image" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	uri, err := h.qr.DecodeQR(req.Image)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid image"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"uri": uri})
}

func (h *OtpHandler) GetAll(c *gin.Context) {
	userID := middleware.GetUserID(c)
	otps, err := h.svc.GetAll(c.Request.Context(), userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	if otps == nil {
		otps = []model.Otp{}
	}
	c.JSON(http.StatusOK, otps)
}

func (h *OtpHandler) GetByID(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid id"})
		return
	}
	userID := middleware.GetUserID(c)
	o, err := h.svc.GetByID(c.Request.Context(), id, userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	if o == nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
		return
	}
	c.JSON(http.StatusOK, o)
}

func (h *OtpHandler) Create(c *gin.Context) {
	var req model.CreateOtpRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	userID := middleware.GetUserID(c)
	o, err := h.svc.Create(c.Request.Context(), req, userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, o)
}

func (h *OtpHandler) Update(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid id"})
		return
	}
	var req model.CreateOtpRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	userID := middleware.GetUserID(c)
	o, err := h.svc.Update(c.Request.Context(), id, req, userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, o)
}

func (h *OtpHandler) Delete(c *gin.Context) {
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

func (h *OtpHandler) Validate(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid id"})
		return
	}
	code := c.Query("code")
	if code == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "code is required"})
		return
	}
	userID := middleware.GetUserID(c)
	valid, err := h.svc.Validate(c.Request.Context(), id, userID, code)
	if err != nil {
		log.Printf("ERROR: %s %s: %v", c.Request.Method, sanitizeLog(c.Request.URL.Path), err)
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid code"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"valid": valid})
}
