package metrics

import (
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/prometheus/client_golang/prometheus/promauto"
)

var (
	requestsTotal = promauto.NewCounterVec(
		prometheus.CounterOpts{
			Name: "http_requests_total",
			Help: "Total number of HTTP requests",
		},
		[]string{"method", "path", "status"},
	)

	requestDuration = promauto.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "http_request_duration_seconds",
			Help:    "Duration of HTTP requests in seconds",
			Buckets: prometheus.DefBuckets,
		},
		[]string{"method", "path"},
	)

	requestsInFlight = promauto.NewGaugeVec(
		prometheus.GaugeOpts{
			Name: "http_requests_in_flight",
			Help: "Number of HTTP requests currently in flight",
		},
		[]string{"method", "path"},
	)
)

func Middleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		path := c.FullPath()
		method := c.Request.Method

		requestsInFlight.WithLabelValues(method, path).Inc()

		c.Next()

		status := strconv.Itoa(c.Writer.Status())
		requestsInFlight.WithLabelValues(method, path).Dec()
		requestsTotal.WithLabelValues(method, path, status).Inc()
		requestDuration.WithLabelValues(method, path).Observe(time.Since(start).Seconds())
	}
}

func PrometheusHandler() gin.HandlerFunc {
	h := promhttp.Handler()
	return func(c *gin.Context) {
		h.ServeHTTP(c.Writer, c.Request)
	}
}

func JSONHandler() gin.HandlerFunc {
	return func(c *gin.Context) {
		mfs, err := prometheus.DefaultGatherer.Gather()
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"success": false, "error": err.Error()})
			return
		}

		result := make([]gin.H, 0, len(mfs))
		for _, mf := range mfs {
			name := mf.GetName()
			if name == "" {
				continue
			}

			entry := gin.H{
				"name": name,
				"help": mf.GetHelp(),
				"type": mf.GetType().String(),
			}

			metrics := make([]gin.H, 0, len(mf.GetMetric()))
			for _, m := range mf.GetMetric() {
				metric := gin.H{}

				labels := make(gin.H)
				for _, l := range m.GetLabel() {
					labels[l.GetName()] = l.GetValue()
				}
				metric["labels"] = labels

				switch mf.GetType().String() {
				case "COUNTER":
					metric["value"] = m.GetCounter().GetValue()
				case "GAUGE":
					metric["value"] = m.GetGauge().GetValue()
				case "HISTOGRAM":
					h := m.GetHistogram()
					metric["sample_count"] = h.GetSampleCount()
					metric["sample_sum"] = h.GetSampleSum()
					buckets := make([]gin.H, 0, len(h.GetBucket()))
					for _, b := range h.GetBucket() {
						buckets = append(buckets, gin.H{
							"cumulative_count": b.GetCumulativeCount(),
							"upper_bound":      b.GetUpperBound(),
						})
					}
					metric["buckets"] = buckets
				case "SUMMARY":
					s := m.GetSummary()
					metric["sample_count"] = s.GetSampleCount()
					metric["sample_sum"] = s.GetSampleSum()
				}

				metrics = append(metrics, metric)
			}
			entry["metrics"] = metrics
			result = append(result, entry)
		}

		c.JSON(http.StatusOK, gin.H{"success": true, "data": result})
	}
}
