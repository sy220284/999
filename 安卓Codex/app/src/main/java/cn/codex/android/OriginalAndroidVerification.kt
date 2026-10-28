package cn.codex.android

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import cn.codex.core.original.OriginalVerificationBridgeContract
import cn.codex.core.string
import kotlinx.serialization.json.*
import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** 实际Android Keystore提供者；私钥只用于系统CryptoObject，不导出到Rust。 */
class OriginalAndroidVerification(private val context:Context) {
    private fun store()=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
    fun deviceSupported():Boolean = context.getSystemService(KeyguardManager::class.java).isDeviceSecure && context.getSystemService(BiometricManager::class.java).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)==BiometricManager.BIOMETRIC_SUCCESS
    private fun check(live:()->Boolean){if(!live())throw VerificationBridgeFailure("interrupted")}
    private fun encoded(bytes:ByteArray)=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun credential(public:ECPublicKey)=buildJsonObject{put("credentialId",encoded(MessageDigest.getInstance("SHA-256").digest(public.encoded)));put("publicPoint",encoded(publicPoint(public)))}
    private fun protected(key:PrivateKey):Boolean {
        val info=KeyFactory.getInstance(key.algorithm,"AndroidKeyStore").getKeySpec(key,KeyInfo::class.java)
        return info.securityLevel in setOf(KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,KeyProperties.SECURITY_LEVEL_STRONGBOX) && info.isUserAuthenticationRequired && info.userAuthenticationValidityDurationSeconds==-1 && info.isUserAuthenticationRequirementEnforcedBySecureHardware && info.userAuthenticationType==KeyProperties.AUTH_BIOMETRIC_STRONG
    }
    suspend fun handle(request:JsonObject,signal:CancellationSignal,live:()->Boolean):JsonObject {
        OriginalVerificationBridgeContract.request(request);check(live)
        val op=request.string("operation")
        if(op=="device")return buildJsonObject{put("supported",deviceSupported())}
        val alias=OriginalVerificationBridgeContract.namespace(request.string("namespace"))
        val keys=store()
        if(op=="status"){
            if(!deviceSupported())return buildJsonObject{put("unavailable","biometricsUnavailable")}
            val key=keys.getKey(alias,null) as? PrivateKey
            if(key==null)return buildJsonObject{put("unavailable","credentialMissing")}
            if(!protected(key))return buildJsonObject{put("unavailable","providerUnavailable")}
            val public=keys.getCertificate(alias).publicKey as? ECPublicKey ?: throw VerificationBridgeFailure("providerError")
            check(live);return buildJsonObject{put("credential",credential(public))}
        }
        if(!OriginalBiometricPrompts.available())throw VerificationBridgeFailure("interrupted")
        if(op=="delete"){
            val old=(keys.getCertificate(alias)?.publicKey as? ECPublicKey)?.let{credential(it).getValue("credentialId")}
            check(live);keys.deleteEntry(alias);check(live)
            return buildJsonObject{put("deletedCredentialId",old ?: JsonNull)}
        }
        if(!deviceSupported())throw VerificationBridgeFailure("biometricsUnavailable")
        if(op=="ensure"){
            var created=false
            if(!keys.containsAlias(alias)){
                check(live)
                val generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore")
                generator.initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(true).setUserAuthenticationParameters(0,KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(true).setUnlockedDeviceRequired(true).build())
                generator.generateKeyPair();created=true
            }
            val key=keys.getKey(alias,null) as? PrivateKey ?: throw VerificationBridgeFailure("credentialMissing")
            if(!protected(key)){if(created)keys.deleteEntry(alias);throw VerificationBridgeFailure("providerUnavailable")}
            check(live)
            val public=keys.getCertificate(alias).publicKey as? ECPublicKey ?: throw VerificationBridgeFailure("providerError")
            return buildJsonObject{put("created",created);put("credential",credential(public))}
        }
        val privateKey=keys.getKey(alias,null) as? PrivateKey ?: throw VerificationBridgeFailure("credentialMissing")
        if(!protected(privateKey))throw VerificationBridgeFailure("providerUnavailable")
        val public=keys.getCertificate(alias).publicKey as? ECPublicKey ?: throw VerificationBridgeFailure("providerError")
        val signature=Signature.getInstance("SHA256withECDSA").apply{initSign(privateKey)}
        val authenticated=OriginalBiometricPrompts.authenticate(signature,request.string("title"),request.string("description"),signal,live)
        check(live);authenticated.update(Base64.getUrlDecoder().decode(request.string("challenge")))
        val proof=authenticated.sign();check(live)
        return buildJsonObject{put("credentialId",credential(public).getValue("credentialId"));put("signature",encoded(proof))}
    }
    companion object {
        private fun coordinate(value:BigInteger):ByteArray {val raw=value.toByteArray();require(raw.size<=33);val out=ByteArray(32);val bytes=if(raw.size==33)raw.copyOfRange(1,33)else raw;bytes.copyInto(out,32-bytes.size);return out}
        fun publicPoint(public:ECPublicKey):ByteArray {require(public.params.curve.field.fieldSize==256);return byteArrayOf(4)+coordinate(public.w.affineX)+coordinate(public.w.affineY)}
    }
}
