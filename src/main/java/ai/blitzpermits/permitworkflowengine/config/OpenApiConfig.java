package ai.blitzpermits.permitworkflowengine.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI permitWorkflowOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Permit Workflow Engine API")
                        .version("v1")
                        .description("Policy-driven multi-jurisdiction municipal permit workflow and lifecycle engine."));
    }
}