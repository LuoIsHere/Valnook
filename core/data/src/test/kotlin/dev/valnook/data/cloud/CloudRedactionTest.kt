package dev.valnook.data.cloud

import dev.valnook.domain.cloud.CloudAccessResult
import dev.valnook.domain.cloud.CloudAuthorizationGrant
import org.junit.Assert.assertFalse
import org.junit.Test

/** Models that carry a token or an authorized address never print it. */
class CloudRedactionTest {
    @Test fun secret_bearing_models_are_redacted() {
        assertFalse(CloudAuthorizationGrant("secret").toString().contains("secret"))
        assertFalse(CloudAccessResult.Granted("secret").toString().contains("secret"))
        assertFalse(CloudHttpRequest("GET", "https://host/secret", mapOf("Authorization" to "secret")).toString().contains("secret"))
    }
}
