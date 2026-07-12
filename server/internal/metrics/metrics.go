// Package metrics:进程指标,/metrics 挂 pprof 的内网端口,与其同一安全边界。
// 指标从省着来:HTTP、任务、周期清理三组,能回答"服务健康吗、任务在失败吗、
// 清理在跑吗"就够;要接监控系统时指标已经在了。
package metrics

import (
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
)

var (
	HTTPRequests = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "gopan_http_requests_total",
		Help: "HTTP 请求数(path 已归一防基数爆炸)",
	}, []string{"path", "status"})

	HTTPDuration = promauto.NewHistogramVec(prometheus.HistogramOpts{
		Name:    "gopan_http_duration_seconds",
		Help:    "HTTP 请求耗时",
		Buckets: []float64{.005, .02, .1, .5, 2, 10, 60},
	}, []string{"path"})

	Tasks = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "gopan_tasks_total",
		Help: "worker 任务执行数",
	}, []string{"kind", "status"})

	Cleanup = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "gopan_cleanup_total",
		Help: "周期清理回收的对象数",
	}, []string{"job"})
)
