package com.example.remotecontrol

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer

private class Sdp(
    private val onCreated: (SessionDescription) -> Unit = {},
    private val onSet: () -> Unit = {},
    private val onError: (String) -> Unit = {}
) : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription?) {
        if (sdp != null) onCreated(sdp)
    }

    override fun onSetSuccess() {
        onSet()
    }

    override fun onCreateFailure(error: String?) {
        onError(error ?: "خطأ غير معروف")
    }

    override fun onSetFailure(error: String?) {
        onError(error ?: "خطأ غير معروف")
    }
}

object WebRtcManager {
    var sender: ((String, JSONObject) -> Unit)? = null
    var onState: ((String) -> Unit)? = null
    var onText: ((String) -> Unit)? = null
    var onScreenStopped: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var channel: DataChannel? = null
    private var remote: String = ""
    private var iceReady = false
    private var remoteSet = false
    private val pendingOutIce = mutableListOf<JSONObject>()
    private val pendingInIce = mutableListOf<IceCandidate>()

    private var localSource: VideoSource? = null
    private var localTrack: VideoTrack? = null
    private var localCapturer: ScreenCapturerAndroid? = null
    private var localHelper: SurfaceTextureHelper? = null
    private var remoteTrack: VideoTrack? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    val isSharing: Boolean get() = localCapturer != null

    fun init(context: Context) {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions()
        )
        val egl = EglBase.create()
        eglBase = egl
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    // يبدأ التقاط الشاشة بدقتها الكاملة ويجهّز مسار الفيديو الذي يُرسَل عند الاتصال
    fun startScreen(context: Context, data: Intent) {
        init(context)
        stopScreen()
        val egl = eglBase ?: return
        val f = factory ?: return
        val helper = SurfaceTextureHelper.create("ScreenCapture", egl.eglBaseContext)
        val source = f.createVideoSource(true)
        val capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() {
                stopScreen()
                main.post { onScreenStopped?.invoke() }
            }
        })
        capturer.initialize(helper, context.applicationContext, source.capturerObserver)
        val m = context.resources.displayMetrics
        capturer.startCapture(m.widthPixels, m.heightPixels, 15)
        localSource = source
        localTrack = f.createVideoTrack("screen0", source)
        localCapturer = capturer
        localHelper = helper
    }

    fun stopScreen() {
        try {
            localCapturer?.stopCapture()
        } catch (e: Exception) {
        }
        localCapturer?.dispose()
        localHelper?.dispose()
        localCapturer = null
        localHelper = null
        localTrack = null
        localSource = null
    }

    fun attachRenderer(r: SurfaceViewRenderer) {
        val egl = eglBase ?: return
        r.init(egl.eglBaseContext, null)
        r.setEnableHardwareScaler(true)
        // يحافظ على كامل محتوى الشاشة ونسبتها دون قص، مع أشرطة عند الحاجة
        r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        remoteRenderer = r
        remoteTrack?.addSink(r)
    }

    fun detachRenderer(r: SurfaceViewRenderer) {
        remoteTrack?.removeSink(r)
        if (remoteRenderer === r) remoteRenderer = null
        r.release()
    }

    // يبدأ الاتصال من الجهة التي أرسلت الطلب
    fun startAsCaller(to: String) {
        createPc(to)
        attach(pc!!.createDataChannel("chat", DataChannel.Init()))
        addVideoForOffer()
        pc!!.createOffer(
            Sdp(
                onCreated = { offer ->
                    pc?.setLocalDescription(Sdp(onError = ::report), offer)
                    sendSdp(to, offer)
                    iceReady = true
                    flushOutIce()
                },
                onError = ::report
            ),
            MediaConstraints()
        )
    }

    // يستقبل رسائل SDP وICE القادمة من الخادم
    fun handleSignal(from: String, data: JSONObject) {
        when (data.optString("kind")) {
            "sdp" -> {
                val type = SessionDescription.Type.fromCanonicalForm(data.getString("type"))
                val desc = SessionDescription(type, data.getString("sdp"))
                if (type == SessionDescription.Type.OFFER) {
                    createPc(from)
                    pc!!.setRemoteDescription(
                        Sdp(
                            onSet = {
                                addLocalIfAny()
                                remoteReady()
                                pc?.createAnswer(
                                    Sdp(
                                        onCreated = { answer ->
                                            pc?.setLocalDescription(Sdp(onError = ::report), answer)
                                            sendSdp(from, answer)
                                            iceReady = true
                                            flushOutIce()
                                        },
                                        onError = ::report
                                    ),
                                    MediaConstraints()
                                )
                            },
                            onError = ::report
                        ),
                        desc
                    )
                } else {
                    pc?.setRemoteDescription(
                        Sdp(onSet = { remoteReady() }, onError = ::report),
                        desc
                    )
                }
            }
            "ice" -> {
                val c = IceCandidate(
                    data.getString("sdpMid"),
                    data.getInt("sdpMLineIndex"),
                    data.getString("candidate")
                )
                if (remoteSet) pc?.addIceCandidate(c) else pendingInIce.add(c)
            }
        }
    }

    fun sendText(text: String): Boolean {
        val dc = channel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        return dc.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false))
    }

    fun close() {
        channel?.close()
        channel = null
        pc?.close()
        pc = null
        remoteRenderer?.let { remoteTrack?.removeSink(it) }
        remoteTrack = null
        iceReady = false
        remoteSet = false
        pendingOutIce.clear()
        pendingInIce.clear()
    }

    private fun addVideoForOffer() {
        val p = pc ?: return
        val t = localTrack
        if (t != null) {
            p.addTrack(t, listOf("screen"))
        } else {
            p.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
        }
    }

    private fun addLocalIfAny() {
        val t = localTrack ?: return
        pc?.addTrack(t, listOf("screen"))
    }

    private fun createPc(to: String) {
        close()
        remote = to
        val config = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        )
        pc = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                report("اتصال ICE: $state")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                val msg = JSONObject()
                    .put("kind", "ice")
                    .put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                if (iceReady) sender?.invoke(remote, msg) else pendingOutIce.add(msg)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {}

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(dc: DataChannel?) {
                if (dc != null) attach(dc)
            }

            override fun onRenegotiationNeeded() {}

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    main.post {
                        remoteTrack = track
                        remoteRenderer?.let { track.addSink(it) }
                    }
                }
            }
        })
    }

    private fun attach(dc: DataChannel) {
        channel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {}

            override fun onStateChange() {
                report("قناة البيانات: ${dc.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer?) {
                val b = buffer ?: return
                val bytes = ByteArray(b.data.remaining())
                b.data.get(bytes)
                val text = String(bytes, Charsets.UTF_8)
                main.post { onText?.invoke(text) }
            }
        })
    }

    private fun sendSdp(to: String, d: SessionDescription) {
        sender?.invoke(
            to,
            JSONObject()
                .put("kind", "sdp")
                .put("type", d.type.canonicalForm())
                .put("sdp", d.description)
        )
    }

    private fun flushOutIce() {
        pendingOutIce.forEach { sender?.invoke(remote, it) }
        pendingOutIce.clear()
    }

    private fun remoteReady() {
        remoteSet = true
        pendingInIce.forEach { pc?.addIceCandidate(it) }
        pendingInIce.clear()
    }

    private fun report(msg: String) {
        main.post { onState?.invoke(msg) }
    }
}
