package com.agentwork.productspecagent.config

import java.nio.file.Path
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("okf.git")
data class OkfGitProperties(
    val worktreeRoot: Path = Path.of("data/okf-worktrees"),
    val username: String = "oauth2",
    val token: String = "",
    val allowFileRemotes: Boolean = false,
)
