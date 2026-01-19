package com.github.albertocavalcante.gvy.jupyter.jenkins

import com.github.albertocavalcante.gvy.jupyter.core.handlers.ExecuteResult
import com.github.albertocavalcante.gvy.jupyter.core.handlers.ExecuteStatus
import com.github.albertocavalcante.gvy.jupyter.core.kernel.core.KernelExecutor

class JenkinsExecutor : KernelExecutor {
    override fun execute(code: String): ExecuteResult = ExecuteResult(
        status = ExecuteStatus.OK,
        result = "Jenkins Kernel (Placeholder): $code",
        stdout = "Executed in Jenkins Mode",
    )
}
