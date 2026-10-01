package dev.sentinellm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude={UserDetailsServiceAutoConfiguration.class,org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration.class})
public class GatewayApplication {
    public static void main(String[] args) {
        // Local service addresses may change after container restarts. Also applies to HTTP and JDBC.
        java.security.Security.setProperty("networkaddress.cache.ttl","5");
        java.security.Security.setProperty("networkaddress.cache.negative.ttl","1");
        if(java.util.Arrays.asList(args).contains("--migrate")) {
            try {Database.migrate(System.getenv("SENTINEL_DB_URL"),System.getenv("SENTINEL_DB_MIGRATION_USER"),System.getenv("SENTINEL_DB_OWNER_PASSWORD"));}
            catch(Exception ex){System.err.println("Database migration failed.");System.exit(1);}return;
        }
        SpringApplication.run(GatewayApplication.class, args);
    }

    @Bean Settings settings(Environment env) { return Settings.load(env); }
    @Bean Contracts contracts() { return new Contracts(); }
    @Bean(destroyMethod="close") RuntimeState runtimeState(Settings settings,Contracts contracts,Environment env){return new RuntimeState(settings,contracts,env);}
    @Bean Observability observability(Environment env) { return new Observability(env); }
    @Bean GatewayEngine engine(Settings settings, Contracts contracts, Environment env,RuntimeState runtime,Observability metrics) {
        String mode=env.getProperty("SENTINEL_TOOL_MODE","demo");
        if (!java.util.Set.of("demo","mcp").contains(mode)) throw new IllegalStateException("Invalid registered tool mode.");
        ToolExecutor executor=mode.equals("mcp") ? new McpTools(env.getProperty("SENTINEL_MCP_BRIDGE_URL","http://mcp-adapter:8100/internal/v1/tools/execute"),env.getProperty("SENTINEL_MCP_KEY","")) : new DemoTools();
        int mockDelay=env.getProperty("SENTINEL_MOCK_DELAY_MS",Integer.class,0);
        if(mockDelay<0||mockDelay>5000)throw new IllegalStateException("Invalid mock latency.");
        Provider provider=settings.provider().equals("mock") ? new DelayedMockProvider(new MockProvider(settings.mockResponse(),true),mockDelay) : new NavigatorProvider(settings);
        return new GatewayEngine(settings, contracts, metrics.inspector(new RemoteInspector(settings, contracts)),
                metrics.provider(provider),
                metrics.audit(runtime.audit),new ToolRegistry(contracts),metrics.tools(executor),metrics.state(runtime.durable),metrics.rates(runtime.rates));
    }
}
