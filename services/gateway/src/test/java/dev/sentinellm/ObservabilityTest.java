package dev.sentinellm;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ObservabilityTest {
    @Test void independent_auth_disabled_without_key_and_constant_route_labels(){
        try(var disabled=new Observability(new MockEnvironment());var m=new Observability(new MockEnvironment().withProperty("SENTINEL_METRICS_KEY","synthetic_metrics_credential_with_32_characters"))){
            assertFalse(disabled.authorized("Bearer "));assertFalse(m.authorized("Bearer app-key"));assertTrue(m.authorized("Bearer synthetic_metrics_credential_with_32_characters"));
            assertEquals("other",Observability.route("/user-secret-path"));assertEquals("management",Observability.route("/api/v1/management/operations/user-secret"));
            for(int i=0;i<500;i++)m.http(Observability.route("/untrusted/"+i),401,System.nanoTime());
            String scrape=m.registry.scrape();assertFalse(scrape.contains("untrusted"));assertFalse(scrape.contains("credential"));assertFalse(scrape.contains("user-secret"));
            assertEquals(500,m.registry.find("sentinel.http.duration").timer().count());
        }
    }
    @Test void admission_is_bounded_and_released(){
        try(var m=new Observability(new MockEnvironment().withProperty("SENTINEL_HTTP_CAPACITY","2"))){
            assertTrue(m.admit());assertTrue(m.admit());assertFalse(m.admit());assertEquals(2,m.registry.find("sentinel.http.active").gauge().value());
            m.release();assertTrue(m.admit());assertEquals(1,m.registry.find("sentinel.admission.rejections").counter().count());
        }
    }
    @Test void failed_audit_does_not_record_successful_decision_and_error_is_preserved(){
        try(var m=new Observability(new MockEnvironment())){
            Audit fail=(d,id,p,o,t)->{throw ApiFailure.unavailable();};
            assertThrows(ApiFailure.class,()->m.audit(fail).write(Contracts.object(),null,"mock","approved",0));
            assertNull(m.registry.find("sentinel.decisions").counter());
            assertEquals(1,m.registry.find("sentinel.dependency.duration").tags("dependency","audit","result","failure").timer().count());
        }
    }
    @Test void all_dependency_names_are_fixed_and_payload_not_exported(){
        try(var m=new Observability(new MockEnvironment())){
            m.provider((body,t)->Contracts.object().put("content","private-prompt-text")).complete(Contracts.object(),10);
            assertFalse(m.registry.scrape().contains("private-prompt-text"));
            assertEquals(1,m.registry.find("sentinel.dependency.duration").tags("dependency","provider","result","success").timer().count());
        }
    }
    @Test void invalid_capacity_or_weak_key_fails_startup(){
        assertThrows(IllegalStateException.class,()->new Observability(new MockEnvironment().withProperty("SENTINEL_HTTP_CAPACITY","0")));
        assertThrows(IllegalStateException.class,()->new Observability(new MockEnvironment().withProperty("SENTINEL_METRICS_KEY","weak")));
    }
}
