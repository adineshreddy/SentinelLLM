package dev.sentinellm;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/management")
final class ManagementApi {
    private final GatewayEngine engine;
    private final Contracts contracts;
    ManagementApi(GatewayEngine engine,Contracts contracts){this.engine=engine;this.contracts=contracts;}
    private JsonNode checked(String schema,JsonNode value){contracts.validate(schema,value,true);try{if(Contracts.JSON.writeValueAsBytes(value).length>524288)throw ApiFailure.unavailable();}catch(java.io.IOException ex){throw ApiFailure.unavailable();}return value;}
    private Identity identity(HttpServletRequest request){return (Identity)request.getAttribute("identity");}
    private static long cursor(String value){try{if(value==null)return Long.MAX_VALUE;if(!value.matches("[1-9][0-9]{0,18}"))throw ApiFailure.invalid();return Long.parseLong(value);}catch(Exception ex){throw ApiFailure.invalid();}}
    private static int size(String value){long count=value==null?50:cursor(value);if(count>100)throw ApiFailure.invalid();return (int)count;}
    private static void query(HttpServletRequest request,Set<String> allowed){if(!allowed.containsAll(request.getParameterMap().keySet()) || request.getParameterMap().values().stream().anyMatch(v->v.length!=1))throw ApiFailure.invalid();}
    @GetMapping("/config") JsonNode configuration(HttpServletRequest request){var state=engine.management(identity(request),false);query(request,Set.of());return checked("config-response",state.configuration(identity(request)));}
    @PutMapping("/config") JsonNode update(HttpServletRequest request){var state=engine.management(identity(request),true);query(request,Set.of());return checked("config-response",state.update(identity(request),(JsonNode)request.getAttribute("body")));}
    @GetMapping("/config/history") JsonNode history(HttpServletRequest request){var state=engine.management(identity(request),false);query(request,Set.of("before","limit"));return checked("config-history",state.history(identity(request),cursor(request.getParameter("before")),size(request.getParameter("limit"))));}
    @GetMapping("/events") JsonNode events(HttpServletRequest request){var state=engine.management(identity(request),false);query(request,Set.of("before","limit","action","stage","operation_id"));
        String action=Objects.requireNonNullElse(request.getParameter("action"),""),stage=Objects.requireNonNullElse(request.getParameter("stage"),""),operation=Objects.requireNonNullElse(request.getParameter("operation_id"),"");
        if(!Set.of("","allow","deny","redact").contains(action)||!Set.of("","input","output","preview","tool_input","tool_output").contains(stage))throw ApiFailure.invalid();
        if(!operation.isEmpty())try{if(!UUID.fromString(operation).toString().equals(operation))throw ApiFailure.invalid();}catch(Exception ex){throw ApiFailure.invalid();}
        return checked("event-page",state.events(identity(request),cursor(request.getParameter("before")),size(request.getParameter("limit")),action,stage,operation));
    }
    @GetMapping("/operations/{operation}") JsonNode trace(@PathVariable String operation,HttpServletRequest request){var state=engine.management(identity(request),false);query(request,Set.of());return checked("operation-trace",state.trace(identity(request),operation));}
}
