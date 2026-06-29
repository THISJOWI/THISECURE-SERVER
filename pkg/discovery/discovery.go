package discovery

import (
	"net/http"
	"sort"

	"github.com/gin-gonic/gin"
)

type EndpointInfo struct {
	Method  string `json:"method"`
	Path    string `json:"path"`
	Handler string `json:"handler,omitempty"`
}

type ServiceInfo struct {
	Name        string         `json:"name"`
	Version     string         `json:"version,omitempty"`
	Endpoints   []EndpointInfo `json:"endpoints"`
	TotalRoutes int            `json:"total_routes"`
}

func Handler(engine *gin.Engine, serviceName string) gin.HandlerFunc {
	return func(c *gin.Context) {
		routes := engine.Routes()

		endpoints := make([]EndpointInfo, 0, len(routes))
		for _, r := range routes {
			endpoints = append(endpoints, EndpointInfo{
				Method:  r.Method,
				Path:    r.Path,
				Handler: r.Handler,
			})
		}

		sort.Slice(endpoints, func(i, j int) bool {
			if endpoints[i].Path != endpoints[j].Path {
				return endpoints[i].Path < endpoints[j].Path
			}
			return endpoints[i].Method < endpoints[j].Method
		})

		info := ServiceInfo{
			Name:        serviceName,
			Endpoints:   endpoints,
			TotalRoutes: len(endpoints),
		}

		c.JSON(http.StatusOK, gin.H{
			"success": true,
			"data":    info,
		})
	}
}
