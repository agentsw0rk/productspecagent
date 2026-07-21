package com.agentwork.productspecagent.api

import com.agentwork.productspecagent.domain.ErrorResponse
import com.agentwork.productspecagent.infrastructure.graphmesh.GraphMeshException
import com.agentwork.productspecagent.service.AssetBundleNotFoundException
import com.agentwork.productspecagent.service.BundleFileNotFoundException
import com.agentwork.productspecagent.service.BundleTooLargeException
import com.agentwork.productspecagent.service.ClarificationNotFoundException
import com.agentwork.productspecagent.service.DecisionNotFoundException
import com.agentwork.productspecagent.service.DocumentNotFoundException
import com.agentwork.productspecagent.service.EmailAlreadyExistsException
import com.agentwork.productspecagent.service.GraphMeshDisabledException
import com.agentwork.productspecagent.service.IllegalBundleEntryException
import com.agentwork.productspecagent.service.InvalidCredentialsException
import com.agentwork.productspecagent.service.InvalidEmailException
import com.agentwork.productspecagent.service.InvalidManifestException
import com.agentwork.productspecagent.service.ManifestIdMismatchException
import com.agentwork.productspecagent.service.MissingManifestException
import com.agentwork.productspecagent.service.ProjectNotFoundException
import com.agentwork.productspecagent.service.TaskNotFoundException
import com.agentwork.productspecagent.service.UnsupportedMediaTypeException
import com.agentwork.productspecagent.service.UnsupportedStepException
import com.agentwork.productspecagent.service.WeakPasswordException
import com.agentwork.productspecagent.service.WizardStepNotCurrentException
import com.agentwork.productspecagent.service.WizardStepNotVisibleException
import com.agentwork.productspecagent.service.OkfChangesetPendingException
import com.agentwork.productspecagent.service.okf.OkfChangesetConflictException
import com.agentwork.productspecagent.service.okf.OkfChangesetNotFoundException
import com.agentwork.productspecagent.service.okf.OkfFeatureProposalException
import com.agentwork.productspecagent.service.okf.OkfGitAuthenticationException
import com.agentwork.productspecagent.service.okf.OkfGitException
import com.agentwork.productspecagent.service.okf.OkfGitInvalidBundleException
import com.agentwork.productspecagent.service.okf.OkfGitPushRejectedException
import com.agentwork.productspecagent.service.okf.OkfGitRemoteAdvancedException
import com.agentwork.productspecagent.service.okf.OkfInvalidRemoteBundleException
import com.agentwork.productspecagent.service.okf.OkfMigrationException
import com.agentwork.productspecagent.service.okf.OkfNotConnectedException
import com.agentwork.productspecagent.service.okf.OkfPendingChangesException
import com.agentwork.productspecagent.service.okf.OkfPushException
import com.agentwork.productspecagent.service.okf.OkfStaleRemoteException
import com.agentwork.productspecagent.service.okf.OkfValidationException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException
import java.time.Instant

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ProjectNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleProjectNotFound(ex: ProjectNotFoundException): ErrorResponse {
        return ErrorResponse(
            error = "NOT_FOUND",
            message = ex.message ?: "Project not found",
            timestamp = Instant.now().toString()
        )
    }

    @ExceptionHandler(DecisionNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleDecisionNotFound(ex: DecisionNotFoundException): ErrorResponse {
        return ErrorResponse(
            error = "NOT_FOUND",
            message = ex.message ?: "Decision not found",
            timestamp = Instant.now().toString()
        )
    }

    @ExceptionHandler(ClarificationNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleClarificationNotFound(ex: ClarificationNotFoundException): ErrorResponse {
        return ErrorResponse(
            error = "NOT_FOUND",
            message = ex.message ?: "Clarification not found",
            timestamp = Instant.now().toString()
        )
    }

    @ExceptionHandler(DocumentNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleDocumentNotFound(ex: DocumentNotFoundException): ErrorResponse {
        return ErrorResponse(
            error = "NOT_FOUND",
            message = ex.message ?: "Document not found",
            timestamp = Instant.now().toString()
        )
    }

    @ExceptionHandler(TaskNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleTaskNotFound(ex: TaskNotFoundException): ErrorResponse {
        return ErrorResponse(
            error = "NOT_FOUND",
            message = ex.message ?: "Task not found",
            timestamp = Instant.now().toString()
        )
    }

    @ExceptionHandler(AssetBundleNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleAssetBundleNotFound(ex: AssetBundleNotFoundException): ErrorResponse {
        return ErrorResponse(
            error = "NOT_FOUND",
            message = ex.message ?: "Asset bundle not found",
            timestamp = Instant.now().toString()
        )
    }

    @ExceptionHandler(WizardStepNotVisibleException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleWizardStepNotVisible(ex: WizardStepNotVisibleException): ErrorResponse =
        ErrorResponse("WIZARD_STEP_NOT_VISIBLE", ex.message ?: "Wizard step is not visible", Instant.now().toString())

    @ExceptionHandler(WizardStepNotCurrentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleWizardStepNotCurrent(ex: WizardStepNotCurrentException): ErrorResponse =
        ErrorResponse("WIZARD_STEP_NOT_CURRENT", ex.message ?: "Wizard step is not current", Instant.now().toString())

    @ExceptionHandler(GraphMeshException.Unavailable::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun handleGraphMeshUnavailable(ex: GraphMeshException.Unavailable): ErrorResponse =
        ErrorResponse("GRAPHMESH_UNAVAILABLE", ex.message ?: "GraphMesh unreachable", Instant.now().toString())

    @ExceptionHandler(GraphMeshException.GraphQlError::class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    fun handleGraphMeshGraphQlError(ex: GraphMeshException.GraphQlError): ErrorResponse =
        ErrorResponse("GRAPHMESH_ERROR", ex.detail, Instant.now().toString())

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    fun handleMaxUpload(ex: MaxUploadSizeExceededException): ErrorResponse =
        ErrorResponse("FILE_TOO_LARGE", "File exceeds maximum size of 20 MB", Instant.now().toString())

    @ExceptionHandler(UnsupportedMediaTypeException::class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    fun handleUnsupportedMime(ex: UnsupportedMediaTypeException): ErrorResponse =
        ErrorResponse("UNSUPPORTED_TYPE", ex.message ?: "Unsupported MIME type", Instant.now().toString())

    @ExceptionHandler(GraphMeshDisabledException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleGraphMeshDisabled(ex: GraphMeshDisabledException): ErrorResponse =
        ErrorResponse("GRAPHMESH_DISABLED_BACKEND", ex.message ?: "GraphMesh is disabled in backend config", Instant.now().toString())

    @ExceptionHandler(BundleFileNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleBundleFileNotFound(ex: BundleFileNotFoundException): ErrorResponse =
        ErrorResponse("NOT_FOUND", ex.message ?: "Bundle file not found", Instant.now().toString())

    @ExceptionHandler(MissingManifestException::class, InvalidManifestException::class,
                      ManifestIdMismatchException::class, UnsupportedStepException::class,
                      IllegalBundleEntryException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleInvalidBundle(ex: RuntimeException): ErrorResponse =
        ErrorResponse("INVALID_BUNDLE", ex.message ?: "Invalid bundle", Instant.now().toString())

    @ExceptionHandler(BundleTooLargeException::class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    fun handleBundleTooLarge(ex: BundleTooLargeException): ErrorResponse =
        ErrorResponse("BUNDLE_TOO_LARGE", ex.message ?: "Bundle too large", Instant.now().toString())

    @ExceptionHandler(InvalidCredentialsException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun handleInvalidCredentials(ex: InvalidCredentialsException): ErrorResponse =
        ErrorResponse("INVALID_CREDENTIALS", ex.message ?: "Login fehlgeschlagen", Instant.now().toString())

    @ExceptionHandler(EmailAlreadyExistsException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleEmailExists(ex: EmailAlreadyExistsException): ErrorResponse =
        ErrorResponse("EMAIL_ALREADY_EXISTS", ex.message ?: "Email bereits registriert", Instant.now().toString())

    @ExceptionHandler(WeakPasswordException::class, InvalidEmailException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleAuthValidation(ex: RuntimeException): ErrorResponse =
        ErrorResponse("VALIDATION_ERROR", ex.message ?: "Ungültige Eingabe", Instant.now().toString())

    @ExceptionHandler(
        OkfValidationException::class,
        OkfMigrationException::class,
        OkfFeatureProposalException::class,
    )
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleOkfValidation(ex: RuntimeException): ErrorResponse =
        ErrorResponse("OKF_VALIDATION_ERROR", ex.message ?: "Invalid OKF input", Instant.now().toString())

    @ExceptionHandler(OkfChangesetNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleOkfChangesetNotFound(ex: OkfChangesetNotFoundException): ErrorResponse =
        ErrorResponse("OKF_CHANGESET_NOT_FOUND", ex.message ?: "OKF changeset not found", Instant.now().toString())

    @ExceptionHandler(
        OkfPendingChangesException::class,
        OkfChangesetPendingException::class,
        OkfChangesetConflictException::class,
        OkfGitRemoteAdvancedException::class,
        OkfStaleRemoteException::class,
        OkfNotConnectedException::class,
    )
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleOkfConflict(ex: RuntimeException): ErrorResponse =
        ErrorResponse("OKF_CONFLICT", ex.message ?: "OKF state conflict", Instant.now().toString())

    @ExceptionHandler(OkfGitInvalidBundleException::class, OkfInvalidRemoteBundleException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun handleInvalidOkfRemote(ex: RuntimeException): ErrorResponse =
        ErrorResponse("OKF_INVALID_REMOTE", ex.message ?: "Remote OKF bundle is invalid", Instant.now().toString())

    @ExceptionHandler(
        OkfGitAuthenticationException::class,
        OkfGitPushRejectedException::class,
        OkfPushException::class,
        OkfGitException::class,
    )
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    fun handleOkfGitFailure(ex: RuntimeException): ErrorResponse =
        ErrorResponse("OKF_GIT_FAILURE", ex.message ?: "OKF Git operation failed", Instant.now().toString())
}
