package com.example.remotecontrol

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.webrtc.Camera2Enumerator
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
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.util.concurrent.Executors

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

    // كل العمليات الثقيلة تتم في هذا الخيط، لا في الخيط الرئيسي
    private val worker = Executors.newSingleThreadExecutor()

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null

    @Volatile private var pc: PeerConnection? = null
    @Volatile private var channel: DataChannel? = null
    @Volatile private var remote = ""
    @Volatile private var iceReady = false
    @Volatile private var remoteSet = false
    private val pendingOutIce = mutableListOf<JSONObject>()
    private val pendingInIce = mutableListOf<IceCandidate>()

    private var localSource: VideoSource? = null
    @Volatile private var localTrack: VideoTrack? = null
    private var localCapturer: VideoCapturer? = null
    private var localHelper: SurfaceTextureHelper? = null

    // تُلمس من الخيط الرئيسي فقط
    private var remoteTrack: VideoTrack? = null
    @Volatile private var remoteRenderer: SurfaceViewRenderer? = null

    fun init(context: Context) {
        CrashLog.install(context)
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

    private fun onWorker(block: () -> Unit) {
        worker.execute {
            try {
                block()
            } catch (e: Exception) {
                report("خطأ WebRTC: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun report(msg: String) {
        main.post { onState?.invoke(msg) }
    }

    private fun finish(ok: Boolean, onResult: (Boolean) -> Unit) {
        main.post { onResult(ok) }
    }

    // يبدأ مصدر الفيديو (شاشة أو كاميرا) ويحفظه كمسار محلي
    private fun beginCapture(
        name: String,
        screen: Boolean,
        makeCapturer: (VideoSource, SurfaceTextureHelper) -> VideoCapturer?
    ): Boolean {
        val egl = eglBase ?: return false
        val f = factory ?: return false
        stopScreenNow()
        val helper = SurfaceTextureHelper.create(name, egl.eglBaseContext)
        val source = f.createVideoSource(screen)
        val capturer = makeCapturer(source, helper)
        if (capturer == null) {
            helper.dispose()
            source.dispose()
            return false
        }
        localSource = source
        localTrack = f.createVideoTrack("${name}0", source)
        localCapturer = capturer
        localHelper = helper
        return true
    }

    fun startScreen(context: Context, data: Intent, onResult: (Boolean) -> Unit) = onWorker {
        init(context)
        val ok = try {
            beginCapture("screen", true) { source, helper ->
                val capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
                    override fun onStop() {
                        onWorker { stopScreenNow() }
                        main.post { onScreenStopped?.invoke() }
                    }
                })
                capturer.initialize(helper, context.applicationContext, source.capturerObserver)
                val m = context.resources.displayMetrics
                capturer.startCapture(m.widthPixels, m.heightPixels, 15)
                capturer
            }
        } catch (e: Exception) {
            report("تعذّر التقاط الشاشة: ${e.javaClass.simpleName}")
            false
        }
        finish(ok, onResult)
    }

    fun startCamera(context: Context, front: Boolean, onResult: (Boolean) -> Unit) = onWorker {
        init(context)
        val enumerator = Camera2Enumerator(context.applicationContext)
        val name = enumerator.deviceNames.firstOrNull {
            if (front) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
        }
        val ok = if (name == null) {
            report("لا توجد كاميرا بهذا الاتجاه")
            false
        } else {
            try {
                val started = beginCapture("camera", false) { source, helper ->
                    enumerator.createCapturer(name, null)?.also {
                        it.initialize(helper, context.applicationContext, source.capturerObserver)
                        it.startCapture(1280, 720, 30)
                    }
                }
                if (!started) report("تعذّر فتح الكاميرا")
                started
            } catch (e: Exception) {
                report("تعذّر فتح الكاميرا: ${e.javaClass.simpleName}")
                false
            }
        }
        finish(ok, onResult)
    }

    private fun stopScreenNow() {
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

    fun stopScreen() = onWorker { stopScreenNow() }

    // يُستدعى من الخيط الرئيسي
    fun attachRenderer(r: SurfaceViewRenderer) {
        val egl = eglBase ?: return
        r.init(egl.eglBaseContext, null)
        r.setEnableHardwareScaler(true)
        r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        remoteRenderer = r
        remoteTrack?.addSink(r)
    }

    fun detachRenderer(r: SurfaceViewRenderer) {
        remoteTrack?.removeSink(r)
        if (remoteRenderer === r) remoteRenderer = null
        r.release()
    }

    fun startAsCaller(to: String) = onWorker {
        createPc(to)
        val p = pc ?: return@onWorker
        attach(p.createDataChannel("chat", DataChannel.Init()))
        addVideoForOffer(p)
        p.createOffer(
            Sdp(
                onCreated = { offer ->
                    p.setLocalDescription(Sdp(onError = ::report), offer)
                    sendSdp(to, offer)
                    iceReady = true
                    flushOutIce()
                },
                onError = ::report
            ),
            MediaConstraints()
        )
    }

    fun handleSignal(from: String, data: JSONObject) = onWorker {
        when (data.optString("kind")) {
            "sdp" -> {
                val type = SessionDescription.Type.fromCanonicalForm(data.getString("type"))
                val desc = SessionDescription(type, data.getString("sdp"))
                if (type == SessionDescription.Type.OFFER) {
                    createPc(from)
                    val p = pc ?: return@onWorker
                    p.setRemoteDescription(
                        Sdp(
                            onSet = {
                                addLocalIfAny(p)
                                remoteReady(p)
                                p.createAnswer(
                                    Sdp(
                                        onCreated = { answer ->
                                            p.setLocalDescription(Sdp(onError = ::report), answer)
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
                    val p = pc ?: return@onWorker
                    p.setRemoteDescription(
                        Sdp(onSet = { remoteReady(p) }, onError = ::report),
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
                if (remoteSet) {
                    pc?.addIceCandidate(c)
                } else {
                    pendingInIce.add(c)
                }
            }
            else -> {}
        }
    }

    fun sendText(text: String): Boolean {
        val dc = channel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        return dc.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false))
    }

    fun close() = onWorker { closeNow() }

    private fun closeNow() {
        channel?.close()
        channel = null
        pc?.close()
        pc = null
        iceReady = false
        remoteSet = false
        pendingOutIce.clear()
        pendingInIce.clear()
        main.post {
            val t = remoteTrack
            val r = remoteRenderer
            if (t != null && r != null) t.removeSink(r)
            remoteTrack = null
        }
    }

    private fun createPc(to: String) {
        closeNow()
        remote = to
        val f = factory ?: return
        val config = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        )
        pc = f.createPeerConnection(config, object : PeerConnection.Observer {
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

    private fun addVideoForOffer(p: PeerConnection) {
        val t = localTrack
        if (t != null) {
            p.addTrack(t, listOf("sharing"))
        } else {
            p.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
        }
    }

    private fun addLocalIfAny(p: PeerConnection) {
        val t = localTrack ?: return
        p.addTrack(t, listOf("sharing"))
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

    private fun remoteReady(p: PeerConnection) {
        remoteSet = true
        pendingInIce.forEach { p.addIceCandidate(it) }
        pendingInIce.clear()
    }
}
