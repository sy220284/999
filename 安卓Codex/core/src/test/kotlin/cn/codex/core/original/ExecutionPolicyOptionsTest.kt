package cn.codex.core.original
import org.junit.Test
import kotlin.test.*
class ExecutionPolicyOptionsTest {
    @Test fun localAndRemoteExecutionForwardTheSameExplicitPolicies(){
        val local=NativeEndpointPlans.executor("a".repeat(64),0,4,true,"inherit",true)
        val remote=RemoteExecutorPlan.build("https://host.invalid","env","phone",RemoteExecutorAuth.ACCOUNT,"","","",concurrent=4,strict=true,pidNamespace="inherit",proxyPrivate=true)
        for(flag in listOf("--concurrent-requests=4","--strict-config","--linux-sandbox-pid-namespace=inherit","--proxy-private-ips-via-upstream")){assertTrue(flag in local.arguments);assertTrue(flag in remote.arguments)}
        assertFailsWith<IllegalArgumentException>{NativeEndpointPlans.executor("a".repeat(64),0,pidNamespace="invented")}
    }
    @Test fun awsCanUseTheOriginalSdkRegionChainAndSharedCoreCanRequireStrictConfig(){
        val aws=RemoteExecutorPlan.build("https://host.invalid","env","phone",RemoteExecutorAuth.AWS,"","","execute-api")
        assertFalse(aws.arguments.any{it.startsWith("--aws-region")})
        assertTrue("--strict-config" in NativeEndpointPlans.daemon("a".repeat(64),0,false,strict=true).arguments)
        assertTrue("--strict-config" in NativeEndpointPlans.unixDaemon("/private/core.sock",false,strict=true).arguments)
    }
    @Test fun readOnlyRuntimeRootsNeverIncludeTheAccountDirectory(){
        val policy=AndroidCommandPolicyDocument.create("/private/project","/native/libcodex_sandbox.so","/private/内置运行环境","/private/原版工具")
        assertEquals(setOf("version","project","program","runtime","tools"),policy.keys)
        assertFailsWith<IllegalArgumentException>{AndroidCommandPolicyDocument.create("/private/project","/native/libcodex_sandbox.so","/private/accounts","/private/原版工具")}
        assertFailsWith<IllegalArgumentException>{AndroidCommandPolicyDocument.create("/private/project","/native/libcodex_sandbox.so","/private/内置运行环境",null)}
    }
}
