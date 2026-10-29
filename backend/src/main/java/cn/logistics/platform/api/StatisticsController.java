package cn.logistics.platform.api;

import cn.logistics.platform.service.TransportStatisticsService;
import cn.logistics.platform.service.HdfsStatisticsService;
import cn.logistics.platform.service.OfflineTaskExportService;
import cn.logistics.platform.security.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/statistics")
@RequireRoles({UserRole.SUPER_ADMIN,UserRole.ADMIN})
public class StatisticsController {

    private final TransportStatisticsService statistics;
    private final HdfsStatisticsService hdfs;
    private final OfflineTaskExportService export;

    public StatisticsController(TransportStatisticsService statistics,HdfsStatisticsService hdfs,OfflineTaskExportService export) {
        this.statistics = statistics;this.hdfs=hdfs;this.export=export;
    }

    @GetMapping("/od")
    public TransportStatisticsService.StatisticsResponse byOd() {
        return statistics.byOd();
    }
    @GetMapping("/offline") public HdfsStatisticsService.Result offline() {return hdfs.statistics();}
    @GetMapping(value="/offline/input",produces="application/x-ndjson;charset=UTF-8")
    public String rawTasks() {return export.rawTasks();}
}
