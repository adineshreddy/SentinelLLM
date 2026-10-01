package dev.sentinellm;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Configuration
class SecurityConfiguration {
    @Bean SecurityFilterChain security(HttpSecurity http, Settings settings,Observability metrics) throws Exception {
        return http.csrf(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable())
                .logout(c->c.disable()).requestCache(c->c.disable())
                .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(c->c.requestMatchers("/health/live","/health/ready").permitAll().requestMatchers("/internal/metrics").hasRole("monitoring").anyRequest().authenticated())
                .exceptionHandling(c->c.authenticationEntryPoint((request,response,ex)->BoundaryFilter.error(response,new ApiFailure(401,"UNAUTHENTICATED"),null))
                        .accessDeniedHandler((request,response,ex)->BoundaryFilter.error(response,new ApiFailure(403,"POLICY_DENIED"),null)))
                .addFilterBefore(new BoundaryFilter(settings,metrics),UsernamePasswordAuthenticationFilter.class).build();
    }
}

final class BoundaryFilter extends OncePerRequestFilter {
    private final Settings settings;
    private final Observability metrics;
    BoundaryFilter(Settings settings,Observability metrics) { this.settings=settings;this.metrics=metrics; }
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String operationId=UUID.randomUUID().toString(); request.setAttribute("operationId",operationId);
        response.setHeader("X-Sentinel-Operation-ID",operationId);
        long started=System.nanoTime(); boolean admitted=false;
        String route=Observability.route(request.getRequestURI());
        try {
            if (request.getRequestURI().equals("/internal/metrics")) {
                if (!request.getMethod().equals("GET") || !metrics.authorized(request.getHeader("Authorization"))) throw new ApiFailure(401,"UNAUTHENTICATED");
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("monitoring",null,List.of(new SimpleGrantedAuthority("ROLE_monitoring"))));
                chain.doFilter(request,response);return;
            }
            if (!Set.of("/health/live","/health/ready").contains(request.getRequestURI())) {
                String header=request.getHeader("Authorization");
                if (header==null || !header.regionMatches(true,0,"Bearer ",0,7) || header.length()>1024) throw new ApiFailure(401,"UNAUTHENTICATED");
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(header.substring(7).getBytes(StandardCharsets.UTF_8)));
                Identity identity=settings.identities().get(hash);
                if (identity==null) throw new ApiFailure(401,"UNAUTHENTICATED");
                var auth=new UsernamePasswordAuthenticationToken(identity,null,identity.roles().stream().map(r->new SimpleGrantedAuthority("ROLE_"+r)).toList());
                SecurityContextHolder.getContext().setAuthentication(auth); request.setAttribute("identity",identity);
                if (!metrics.admit()) throw new ApiFailure(429,"RATE_LIMITED");
                admitted=true;
                if (Set.of("POST","PUT").contains(request.getMethod())) {
                    if (request.getContentType()==null || !request.getContentType().toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;.*)?")) throw ApiFailure.invalid();
                    if (request.getContentLengthLong()>262144) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
                    byte[] body=request.getInputStream().readNBytes(262145);
                    if (body.length>262144) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
                    request.setAttribute("body",Contracts.parse(body,false));
                }
            }
            chain.doFilter(request,response);
        } catch (ApiFailure ex) { error(response,ex,operationId); }
        catch (Exception ex) { error(response,ApiFailure.unavailable(),operationId); }
        finally { if(admitted)metrics.release();if(!route.equals("metrics")&&!route.equals("health"))metrics.http(route,response.getStatus(),started); }
    }
    static void error(HttpServletResponse response,ApiFailure failure,String operationId) throws IOException {
        if (response.isCommitted()) return;
        response.setStatus(failure.status);response.setContentType("application/json");
        if (failure.status==401) response.setHeader("WWW-Authenticate","Bearer");
        if (failure.status==429) response.setHeader("Retry-After","60");
        var body=Contracts.object();var error=body.putObject("error").put("code",failure.code).put("message",failure.getMessage());
        if (operationId!=null) error.put("operation_id",operationId);
        if (failure.decisionId!=null) error.put("decision_id",failure.decisionId);
        response.getOutputStream().write(Contracts.JSON.writeValueAsBytes(body));
    }
}

@RestController
class GatewayController {
    private final GatewayEngine engine;
    GatewayController(GatewayEngine engine) { this.engine=engine; }
    @GetMapping("/health/live") Map<String,String> health() { return Map.of("status","up"); }
    @GetMapping("/health/ready") Map<String,String> ready() {engine.ready();return Map.of("status","up");}
    @PostMapping("/v1/chat/completions") JsonNode chat(HttpServletRequest request) {
        return engine.run("chat",(JsonNode)request.getAttribute("body"),(Identity)request.getAttribute("identity"),(String)request.getAttribute("operationId"));
    }
    @GetMapping("/api/v1/tools") JsonNode tools(HttpServletRequest request) { return engine.run("discovery",null,(Identity)request.getAttribute("identity"),(String)request.getAttribute("operationId")); }
    @PostMapping("/api/v1/tools/execute") JsonNode execute(HttpServletRequest request) {
        return engine.run("tool",(JsonNode)request.getAttribute("body"),(Identity)request.getAttribute("identity"),(String)request.getAttribute("operationId"));
    }
    @PostMapping("/api/v1/inspect") JsonNode preview(HttpServletRequest request) {
        return engine.run("preview",(JsonNode)request.getAttribute("body"),(Identity)request.getAttribute("identity"),(String)request.getAttribute("operationId"));
    }
}

@RestControllerAdvice
class ErrorHandler {
    @ExceptionHandler(ApiFailure.class) void failure(ApiFailure ex,HttpServletRequest request,HttpServletResponse response) throws IOException {
        BoundaryFilter.error(response,ex,(String)request.getAttribute("operationId"));
    }
    @ExceptionHandler(Exception.class) void unknown(Exception ex,HttpServletRequest request,HttpServletResponse response) throws IOException {
        BoundaryFilter.error(response,ApiFailure.unavailable(),(String)request.getAttribute("operationId"));
    }
}
