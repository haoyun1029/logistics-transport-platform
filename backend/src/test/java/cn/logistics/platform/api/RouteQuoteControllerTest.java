package cn.logistics.platform.api;

import cn.logistics.platform.security.*;
import cn.logistics.platform.service.*;
import cn.logistics.platform.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RouteQuoteController.class)
class RouteQuoteControllerTest {
    @MockitoBean AuthService auth;
    @MockitoBean AmapTruckRouteClient client;
    @MockitoBean OsrmRoadRouteClient openRoadClient;
    @MockitoBean RouteQuoteRepository quotes;
    @MockitoBean RouteVerificationAttemptRepository attempts;
    @Autowired MockMvc mvc;
    void login(UserRole role) { when(auth.findPrincipal("quote-test")).thenReturn(Optional.of(new UserPrincipal(7,"test","Test",role))); }
    @Test void driverAndAnonymousCannotQueryOrReadQuoteMaterials() throws Exception {
        mvc.perform(get("/api/routes/truck-quote-history")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/routes/truck-quote-history/1")).andExpect(status().isUnauthorized());
        login(UserRole.DRIVER);
        mvc.perform(get("/api/routes/truck-quote-history").header("Authorization","Bearer quote-test")).andExpect(status().isForbidden());
        mvc.perform(get("/api/routes/truck-quote-history/1").header("Authorization","Bearer quote-test")).andExpect(status().isForbidden());
        mvc.perform(post("/api/routes/truck-quote").header("Authorization","Bearer quote-test").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/routes/road-geometry-quote").header("Authorization","Bearer quote-test").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/routes/open-road-quote").header("Authorization","Bearer quote-test").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(client,openRoadClient,quotes,attempts);
    }
    @Test void permissionFailurePersistsCodeAndResponseWithoutApprovingAnything() throws Exception {
        login(UserRole.ADMIN);
        String excerpt="{\"errcode\":10012,\"errmsg\":\"INSUFFICIENT_PRIVILEGES\"}";
        when(client.quote(any())).thenThrow(new AmapTruckRouteClient.MapServiceException("10012","INSUFFICIENT_PRIVILEGES",excerpt));
        when(attempts.insertFailure(any(),eq(7L),eq("AMAP_10012"),contains("INSUFFICIENT_PRIVILEGES"),eq(excerpt))).thenReturn(9L);
        mvc.perform(post("/api/routes/truck-quote").header("Authorization","Bearer quote-test").contentType("application/json").content("{}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.attemptId").value(9))
                .andExpect(jsonPath("$.verificationStatus").value("FAILED")).andExpect(jsonPath("$.failureReason").value(org.hamcrest.Matchers.containsString("10012")));
        verifyNoInteractions(quotes);
    }
    @Test void authorizedHistoryReadsPersistedSuccessfulQuotes() throws Exception {
        login(UserRole.ADMIN); when(quotes.findRecent(5)).thenReturn(List.of());
        mvc.perform(get("/api/routes/truck-quote-history?limit=5").header("Authorization","Bearer quote-test")).andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(quotes).findRecent(5); verifyNoInteractions(client);
    }
    @Test void missingSavedQuoteIs404WithoutMapRequestOrWrites() throws Exception {
        login(UserRole.ADMIN); when(quotes.findById(999)).thenReturn(Optional.empty());
        mvc.perform(get("/api/routes/truck-quote-history/999").header("Authorization","Bearer quote-test")).andExpect(status().isNotFound());
        verifyNoInteractions(client, attempts); verify(quotes, never()).insert(any(), anyLong());
    }
    @Test void detailRestoresSavedMaterialAndPreservesItsIdWithoutUpstreamQuery() throws Exception {
        login(UserRole.DISPATCHER);
        var time = java.time.Instant.parse("2026-10-24T10:00:00Z");
        when(quotes.findById(3)).thenReturn(Optional.of(new RouteQuoteRepository.QuoteView(3,"A","B","{}","{}",10,15,"AMAP_DRIVING_ROAD_GEOMETRY_V3","PENDING",time)));
        mvc.perform(get("/api/routes/truck-quote-history/3").header("Authorization","Bearer quote-test")).andExpect(status().isOk()).andExpect(jsonPath("$.quoteId").value(3));
        verify(client).restoreQuote("A","B","{}","{}",time,"AMAP_DRIVING_ROAD_GEOMETRY_V3");
        verify(client,never()).quote(any()); verify(client,never()).quoteRoadGeometry(any());
        verify(quotes,never()).insert(any(),anyLong()); verifyNoInteractions(attempts);
    }
    @Test void openRoadFailureRetainsViaAndAlternativeParameters() throws Exception {
        login(UserRole.ADMIN);
        when(openRoadClient.quote(any())).thenThrow(new OsrmRoadRouteClient.RoadServiceException("NoRoute","{\"code\":\"NoRoute\"}"));
        when(attempts.insertFailure(any(),eq(7L),eq("OSRM_NoRoute"),contains("NoRoute"),anyString(),anyMap())).thenReturn(6L);
        mvc.perform(post("/api/routes/open-road-quote").header("Authorization","Bearer quote-test").contentType("application/json")
                .content("{\"origin\":\"120,30\",\"destination\":\"120.2,30.2\",\"originCity\":\"A\",\"destinationCity\":\"B\",\"via\":[\"120.1,30.1\"],\"routeIndex\":1}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.attemptId").value(6));
        verify(attempts).insertFailure(any(),eq(7L),eq("OSRM_NoRoute"),contains("NoRoute"),anyString(),argThat(p ->
                p.get("routeIndex").equals(1) && p.get("via").equals(List.of("120.1,30.1")) && p.get("coordinateSystem").equals("WGS84")));
        verifyNoInteractions(client,quotes);
    }
    @Test void openRoadSavedDetailUsesItsOwnParserAndDoesNotQueryEitherProvider() throws Exception {
        login(UserRole.ADMIN); var time=java.time.Instant.parse("2026-10-24T10:00:00Z");
        when(quotes.findById(2)).thenReturn(Optional.of(new RouteQuoteRepository.QuoteView(2,"A","B","{}","{}",10,15,OsrmRoadRouteClient.SOURCE,"PENDING",time)));
        mvc.perform(get("/api/routes/truck-quote-history/2").header("Authorization","Bearer quote-test")).andExpect(status().isOk()).andExpect(jsonPath("$.quoteId").value(2));
        verify(openRoadClient).restoreQuote("A","B","{}","{}",time); verify(openRoadClient,never()).quote(any());
        verifyNoInteractions(client,attempts); verify(quotes,never()).insert(any(),anyLong());
    }
}
