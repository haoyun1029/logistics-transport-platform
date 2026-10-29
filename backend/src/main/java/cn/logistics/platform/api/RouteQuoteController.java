package cn.logistics.platform.api;

import cn.logistics.platform.security.RequireRoles;
import cn.logistics.platform.security.UserRole;
import cn.logistics.platform.security.ApiAuthorizationInterceptor;
import cn.logistics.platform.security.UserPrincipal;
import cn.logistics.platform.repository.RouteQuoteRepository;
import cn.logistics.platform.repository.RouteVerificationAttemptRepository;
import cn.logistics.platform.service.AmapTruckRouteClient;
import cn.logistics.platform.service.OsrmRoadRouteClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/routes")
@RequireRoles({UserRole.SUPER_ADMIN, UserRole.ADMIN, UserRole.DISPATCHER})
public class RouteQuoteController {

    private final AmapTruckRouteClient routeClient;
    private final RouteQuoteRepository quotes;
    private final RouteVerificationAttemptRepository attempts;
    private final OsrmRoadRouteClient openRoadClient;

    public RouteQuoteController(AmapTruckRouteClient routeClient, RouteQuoteRepository quotes,
                                RouteVerificationAttemptRepository attempts, OsrmRoadRouteClient openRoadClient) {
        this.routeClient = routeClient;
        this.quotes = quotes;
        this.attempts = attempts;
        this.openRoadClient = openRoadClient;
    }

    @PostMapping("/truck-quote")
    public ResponseEntity<?> truckQuote(
            @RequestBody AmapTruckRouteClient.TruckRouteRequest request,
            HttpServletRequest httpRequest) {
        return query(request, httpRequest, true);
    }

    @PostMapping("/road-geometry-quote")
    public ResponseEntity<?> roadGeometryQuote(@RequestBody AmapTruckRouteClient.TruckRouteRequest request,
                                               HttpServletRequest httpRequest) {
        return query(request, httpRequest, false);
    }

    private ResponseEntity<?> query(AmapTruckRouteClient.TruckRouteRequest request, HttpServletRequest httpRequest, boolean truck) {
        UserPrincipal actor = (UserPrincipal) httpRequest.getAttribute(ApiAuthorizationInterceptor.PRINCIPAL_ATTRIBUTE);
        try {
            AmapTruckRouteClient.TruckRouteQuote quote = truck ? routeClient.quote(request) : routeClient.quoteRoadGeometry(request);
            long quoteId = quotes.insert(quote, actor.id());
            return ResponseEntity.ok(new QuoteResponse(quoteId, quote));
        } catch (RuntimeException exception) {
            String reason = rootMessage(exception);
            long attemptId = attempts.insertFailure(request, actor.id(), responseStatus(exception), (truck ? "货车规划：" : "普通道路资料（未核验货车）：") + reason,
                    exception instanceof AmapTruckRouteClient.MapServiceException map ? map.responseExcerpt() : null);
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new QuoteFailureResponse(attemptId, "FAILED", "已记录路线核验失败，排除出正式优化。", reason));
        }
    }

    @PostMapping("/open-road-quote")
    public ResponseEntity<?> openRoadQuote(@RequestBody OsrmRoadRouteClient.RoadRequest request, HttpServletRequest httpRequest) {
        UserPrincipal actor = (UserPrincipal) httpRequest.getAttribute(ApiAuthorizationInterceptor.PRINCIPAL_ATTRIBUTE);
        try {
            var quote = openRoadClient.quote(request);
            return ResponseEntity.ok(new QuoteResponse(quotes.insert(quote, actor.id()), quote));
        } catch (RuntimeException exception) {
            String reason = rootMessage(exception);
            Map<String, Object> parameters = new java.util.LinkedHashMap<>();
            parameters.put("dataSource", OsrmRoadRouteClient.SOURCE); parameters.put("coordinateSystem", "WGS84");
            parameters.put("via", request.via()); parameters.put("routeIndex", request.routeIndex());
            long attemptId = attempts.insertFailure(request.auditRequest(), actor.id(),
                    exception instanceof OsrmRoadRouteClient.RoadServiceException e ? "OSRM_" + e.code() : "CLIENT_ERROR",
                    "OSRM 普通道路资料（未核验货车）：" + reason,
                    exception instanceof OsrmRoadRouteClient.RoadServiceException e ? e.response() : null, parameters);
            return ResponseEntity.unprocessableEntity().body(new QuoteFailureResponse(attemptId, "FAILED", "已保存失败资料，排除出正式优化。", reason));
        }
    }

    @GetMapping("/truck-quotes")
    public List<RouteVerificationAttemptRepository.AttemptView> recentAttempts(
            @RequestParam(defaultValue = "20") int limit) {
        return attempts.findRecent(limit);
    }

    @GetMapping("/truck-quote-history")
    public List<RouteQuoteRepository.QuoteView> recentQuotes(@RequestParam(defaultValue = "20") int limit) {
        return quotes.findRecent(limit);
    }

    @GetMapping("/truck-quote-history/{id}")
    public ResponseEntity<QuoteResponse> savedQuote(@PathVariable long id) {
        return quotes.findById(id).map(saved -> ResponseEntity.ok(new QuoteResponse(saved.id(),
                OsrmRoadRouteClient.SOURCE.equals(saved.dataSource())
                        ? openRoadClient.restoreQuote(saved.originCity(), saved.destinationCity(), saved.requestParametersJson(), saved.responseJson(), saved.requestedAt())
                        : routeClient.restoreQuote(saved.originCity(), saved.destinationCity(), saved.requestParametersJson(),
                        saved.responseJson(), saved.requestedAt(), saved.dataSource()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record QuoteResponse(long quoteId, AmapTruckRouteClient.TruckRouteQuote quote) {}

    public record QuoteFailureResponse(long attemptId, String verificationStatus,
                                       String message, String failureReason) {}

    private static String rootMessage(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null && (current.getMessage() == null || current.getMessage().isBlank())) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message.replaceAll("(?i)([?&]key=)[^&\\s\"']+","$1REDACTED");
    }

    private static String responseStatus(Throwable exception) {
        if (exception instanceof AmapTruckRouteClient.MapServiceException map) return "AMAP_" + map.code();
        if (exception instanceof org.springframework.web.client.HttpStatusCodeException http) {
            return "HTTP_" + http.getStatusCode().value();
        }
        if (exception.getMessage() != null && exception.getMessage().contains("未返回成功结果")) {
            return "MAP_RESPONSE_ERROR";
        }
        return "CLIENT_ERROR";
    }
}
