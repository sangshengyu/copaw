package cn.sangshy.sa;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for application startup.
 * Ported from Python: tests/integrated/test_app_startup.py
 * 
 * Uses @SpringBootTest to verify:
 * - Spring context loads successfully
 * - /health (root) endpoint is accessible
 * - /version endpoint returns version information
 */
@SpringBootTest
@AutoConfigureMockMvc
class AppStartupTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
        // Verifies that the Spring context loads successfully
        // If this test passes, the application starts without errors
    }

    @Test
    void healthEndpoint_shouldReturnRunningStatus() throws Exception {
        // When: Call root endpoint (health check)
        mockMvc.perform(get("/"))
                // Then: Should return 200 OK
                .andExpect(status().isOk())
                // And: Response should contain version and status
                .andExpect(jsonPath("$.version").exists())
                .andExpect(jsonPath("$.status").value("running"));
    }

    @Test
    void versionEndpoint_shouldReturnVersionInfo() throws Exception {
        // When: Call version endpoint
        mockMvc.perform(get("/version"))
                // Then: Should return 200 OK
                .andExpect(status().isOk())
                // And: Response should contain version string
                .andExpect(jsonPath("$.version").exists())
                .andExpect(jsonPath("$.version").isString());
    }

    @Test
    void versionEndpoint_shouldReturnValidVersionFormat() throws Exception {
        // When: Call version endpoint
        mockMvc.perform(get("/version"))
                // Then: Version should match semantic versioning format (e.g., 2.0.0-SNAPSHOT)
                .andExpect(jsonPath("$.version").value(org.hamcrest.Matchers.matchesPattern("^[0-9]+\\.[0-9]+\\.[0-9]+(-.*)?$")));
    }
}
