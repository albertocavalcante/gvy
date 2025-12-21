package com.github.albertocavalcante.groovyjupyter.jenkins

import com.github.albertocavalcante.groovyjupyter.handlers.ExecuteResult
import com.github.albertocavalcante.groovyjupyter.handlers.ExecuteStatus
import com.github.albertocavalcante.groovyjupyter.kernel.core.KernelExecutor

class JenkinsExecutor : KernelExecutor {
    override fun execute(code: String): ExecuteResult = ExecuteResult(
        status = ExecuteStatus.OK,
        result = "Jenkins Kernel (Placeholder): $code",
        stdout = "Executed in Jenkins Mode",
    )
}
