package cn.codex.android
import cn.codex.core.original.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.Executors
import kotlinx.serialization.json.*
class OriginalCliControllerTest {
    @Test fun applyingCloudChangesUsesTheExactApprovedPreviewAndRejectsOldPreview()=runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        val plans=mutableListOf<OriginalCliPlan>()
        val c=OriginalCliController(scope,{false},OriginalMutationLifecycle.None){plan->plans+=plan;OriginalCliResult(0,if(plan.arguments[1]=="diff")"diff --git a/a b/a\n"else"applied")}
        try{
            withContext(dispatcher){c.diff("task",1)};withTimeout(3000){c.state.first{!it.busy}}
            val old=requireNotNull(c.state.value.preview)
            withContext(dispatcher){c.diff("task",2)};withTimeout(3000){c.state.first{!it.busy}}
            withContext(dispatcher){c.apply(old)};assertEquals(2,plans.size)
            val approved=requireNotNull(c.state.value.preview);withContext(dispatcher){c.apply(approved)}
            withTimeout(3000){c.state.first{!it.busy}}
            assertEquals(approved.text,plans.last().expectedDiff);assertNull(c.state.value.preview)
        }finally{scope.cancel();dispatcher.close()}
    }
    @Test fun cancellationKeepsAnUnconfirmedJournalOutcomeAndDoesNotReplay()=runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        val entered=CompletableDeferred<Unit>();val outcomes=mutableListOf<Boolean>();var calls=0
        val lifecycle=object:OriginalMutationLifecycle{
            override fun begin()="cloud"
            override fun finish(ticket:String,confirmed:Boolean){outcomes+=confirmed}
        }
        val c=OriginalCliController(scope,{false},lifecycle){calls++;entered.complete(Unit);awaitCancellation()}
        try{
            withContext(dispatcher){c.create("env","任务",null,1)};entered.await()
            withContext(dispatcher){c.stop()};withTimeout(3000){c.state.first{!it.busy}}
            assertEquals(1,calls);assertEquals(listOf(false),outcomes);assertNotNull(c.state.value.error)
        }finally{scope.cancel();dispatcher.close()}
    }
    @Test fun migrationRequiresACompletedInspectionAndReconnectInvalidatesIt()=runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        val c=OriginalCliController(scope,{false},OriginalMutationLifecycle.None){OriginalCliResult(0,"{}")}
        try{
            withContext(dispatcher){try{c.maintain(OriginalCliCommands.rollouts(true));fail("缺少检查应停止")}catch(_:IllegalStateException){}}
            withContext(dispatcher){c.maintain(OriginalCliCommands.rollouts(false))};withTimeout(3000){c.state.first{!it.busy}}
            assertTrue(c.state.value.migrationInspected)
            withContext(dispatcher){c.invalidateMaintenance()};assertFalse(c.state.value.migrationInspected)
        }finally{scope.cancel();dispatcher.close()}
    }

    @Test fun latestTaskApplyDoesNotUseCloudAttemptAndClearsPreviewOnlyAfterSuccess()=runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        val plans=mutableListOf<OriginalCliPlan>()
        val controller=OriginalCliController(scope,{false},OriginalMutationLifecycle.None){plan->plans+=plan;OriginalCliResult(0,if("--preview" in plan.arguments)"diff --git a/a b/a\n"else"applied")}
        try{
            withContext(dispatcher){controller.latestDiff("task")};withTimeout(3000){controller.state.first{!it.busy}}
            val preview=requireNotNull(controller.state.value.preview);assertTrue(preview.latest)
            withContext(dispatcher){controller.apply(preview)};withTimeout(3000){controller.state.first{!it.busy}}
            assertEquals(listOf("apply","task"),plans.last().arguments);assertEquals(preview.text,plans.last().expectedDiff);assertNull(controller.state.value.preview)
        }finally{scope.cancel();dispatcher.close()}
    }

    @Test fun failedDoctorReportStillKeepsTheActualStructuredFailure()=runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        val report="""{"schemaVersion":1,"generatedAt":"now","overallStatus":"fail","codexVersion":"test","checks":{"sandbox.helpers":{"id":"sandbox.helpers","status":"fail","summary":"unsupported","details":{}}}}"""
        val controller=OriginalCliController(scope,{false},OriginalMutationLifecycle.None){OriginalCliResult(1,report)}
        try{withContext(dispatcher){controller.maintain(OriginalCliCommands.doctor())};withTimeout(3000){controller.state.first{!it.busy}};assertEquals("fail",controller.state.value.doctor?.checks?.single()?.status);assertNotNull(controller.state.value.error)}finally{scope.cancel();dispatcher.close()}
    }

    @Test fun mcpLogoutUsesOnlyTheCurrentPreviewAndKeepsTheExpectedCredentialTarget()=runBlocking{
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher);val plans=mutableListOf<OriginalCliPlan>()
        val controller=OriginalCliController(scope,{false},OriginalMutationLifecycle.None){plan->plans+=plan;OriginalCliResult(0,if("--preview" in plan.arguments)"""{"name":"server","sharedEnterprise":true,"sha256":"${"a".repeat(64)}"}""" else "done")}
        try{
            withContext(dispatcher){controller.previewMcpLogout("server")};withTimeout(3000){controller.state.first{!it.busy}};val old=requireNotNull(controller.state.value.mcpLogout)
            withContext(dispatcher){controller.dismissMcpLogout();controller.logoutMcp(old)};assertEquals(1,plans.size)
            withContext(dispatcher){controller.previewMcpLogout("server")};withTimeout(3000){controller.state.first{!it.busy}};val current=requireNotNull(controller.state.value.mcpLogout)
            withContext(dispatcher){controller.logoutMcp(current)};withTimeout(3000){controller.state.first{!it.busy}}
            assertEquals("a".repeat(64),plans.last().arguments.last());assertTrue("--expected-sha256" in plans.last().arguments);assertNull(controller.state.value.mcpLogout)
        }finally{scope.cancel();dispatcher.close()}
    }
    @Test fun deviceCodeProgressIsTransientAndLateEventsCannotReviveIt()=runBlocking{
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher);val finish=CompletableDeferred<Unit>();var progress:((String)->Unit)?=null
        val event="""{"androidDeviceCode":1,"verificationUrl":"https://issuer.invalid/codex/device","userCode":"ABCD-EFGH"}"""
        val controller=OriginalCliController(scope,{false},OriginalMutationLifecycle.None){plan->progress=plan.progress;plan.progress?.invoke(event);finish.await();OriginalCliResult(0,event)}
        try{
            withContext(dispatcher){controller.maintain(OriginalStandaloneCommands.loginDevice("",""))};withTimeout(3000){controller.state.first{it.deviceCode!=null}};assertEquals("ABCD-EFGH",controller.state.value.deviceCode?.get("userCode")?.jsonPrimitive?.content)
            finish.complete(Unit);withTimeout(3000){controller.state.first{!it.busy}};assertNull(controller.state.value.deviceCode);assertFalse(controller.state.value.output.contains("ABCD-EFGH"))
            progress?.invoke(event);assertNull(controller.state.value.deviceCode)
        }finally{scope.cancel();dispatcher.close()}
    }
}
