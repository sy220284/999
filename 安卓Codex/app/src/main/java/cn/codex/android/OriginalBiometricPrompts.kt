package cn.codex.android

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import java.security.Signature
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 仅当前可见Activity可弹系统强生物认证；CryptoObject绑定每次签名，不能用普通成功回调放行。 */
object OriginalBiometricPrompts {
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var activity=WeakReference<Activity>(null)
    private val signals=java.util.concurrent.ConcurrentHashMap.newKeySet<CancellationSignal>()
    fun attach(value:Activity){activity=WeakReference(value)}
    fun detach(value:Activity){if(activity.get()===value){activity.clear();signals.forEach{it.cancel()};signals.clear()}}
    fun available()=activity.get()?.let{!it.isFinishing && !it.isDestroyed}==true
    suspend fun authenticate(signature:Signature,title:String,description:String,signal:CancellationSignal,live:()->Boolean):Signature = suspendCancellableCoroutine{continuation->
        signals+=signal
        continuation.invokeOnCancellation{signal.cancel();signals-=signal}
        main.post{
            val owner=activity.get()
            if(owner==null || owner.isFinishing || owner.isDestroyed || !live() || signal.isCanceled){
                signals-=signal;if(continuation.isActive)continuation.resumeWithException(VerificationBridgeFailure("interrupted"));return@post
            }
            val prompt=BiometricPrompt.Builder(owner).setTitle(title).setDescription(description)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("取消",owner.mainExecutor){_,_->signal.cancel()}.build()
            try{
                prompt.authenticate(BiometricPrompt.CryptoObject(signature),signal,owner.mainExecutor,object:BiometricPrompt.AuthenticationCallback(){
                    override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){
                        signals-=signal
                        if(!continuation.isActive)return
                        val verified=result.cryptoObject?.signature
                        if(live() && !signal.isCanceled && activity.get()===owner && verified===signature)continuation.resume(verified)
                        else continuation.resumeWithException(VerificationBridgeFailure("interrupted"))
                    }
                    override fun onAuthenticationError(errorCode:Int,errString:CharSequence){
                        signals-=signal
                        if(continuation.isActive)continuation.resumeWithException(VerificationBridgeFailure(if(errorCode in setOf(BiometricPrompt.BIOMETRIC_ERROR_CANCELED,BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,BiometricPrompt.BIOMETRIC_ERROR_NEGATIVE_BUTTON))"cancelled" else "authenticationFailed"))
                    }
                    // 未匹配时系统允许重试，不把一次不匹配当成功，也不返回伪签名。
                })
            }catch(_:Exception){signals-=signal;if(continuation.isActive)continuation.resumeWithException(VerificationBridgeFailure("providerError"))}
        }
    }
}
class VerificationBridgeFailure(val category:String):Exception(category)
