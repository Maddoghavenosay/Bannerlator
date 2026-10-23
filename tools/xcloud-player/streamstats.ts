// Small stats line over the stream (resolution, fps, bitrate, ping, packet loss, decode time), from WebRTC getStats.

export class StreamStats {
    private element: HTMLDivElement
    private timer: ReturnType<typeof setInterval> | undefined
    private last = { bytes: 0, time: 0, decode: 0, frames: 0, lost: 0, received: 0 }

    constructor(private pc: RTCPeerConnection) {
        this.element = document.createElement('div')
        Object.assign(this.element.style, {
            position: 'absolute', top: '8px', left: '8px', zIndex: '3', padding: '3px 8px', borderRadius: '6px',
            background: 'rgba(0,0,0,0.55)', color: '#e8e8e8', font: '12px monospace', pointerEvents: 'none', display: 'none',
        })
        document.body.appendChild(this.element)
    }

    show(visible: boolean) {
        this.element.style.display = visible ? 'block' : 'none'
        if (visible && this.timer === undefined) { this.timer = setInterval(() => this.update(), 1000); this.update() }
        if (!visible && this.timer !== undefined) { clearInterval(this.timer); this.timer = undefined }
    }

    private async update() {
        const report = await this.pc.getStats()
        let text: string[] = []
        let rtt = -1
        report.forEach((s: any) => {
            if (s.type === 'inbound-rtp' && s.kind === 'video') {
                const now = s.timestamp
                const seconds = this.last.time > 0 ? (now - this.last.time) / 1000 : 0
                const mbps = seconds > 0 ? ((s.bytesReceived - this.last.bytes) * 8 / seconds / 1e6) : 0
                const frames = (s.framesDecoded ?? 0) - this.last.frames
                const decodeMs = frames > 0 ? ((s.totalDecodeTime ?? 0) - this.last.decode) * 1000 / frames : 0
                const lost = (s.packetsLost ?? 0) - this.last.lost
                const received = (s.packetsReceived ?? 0) - this.last.received
                const loss = lost + received > 0 ? lost * 100 / (lost + received) : 0
                text.push(`${s.frameWidth ?? '?'}x${s.frameHeight ?? '?'}`, `${Math.round(s.framesPerSecond ?? 0)} fps`,
                    `${mbps.toFixed(1)} Mb/s`, `decode ${decodeMs.toFixed(1)} ms`, `loss ${loss.toFixed(1)}%`)
                this.last = { bytes: s.bytesReceived, time: now, decode: s.totalDecodeTime ?? 0, frames: s.framesDecoded ?? 0,
                    lost: s.packetsLost ?? 0, received: s.packetsReceived ?? 0 }
            } else if (s.type === 'candidate-pair' && s.state === 'succeeded' && s.nominated && s.currentRoundTripTime !== undefined) {
                rtt = s.currentRoundTripTime * 1000
            }
        })
        if (rtt >= 0) text.splice(2, 0, `ping ${Math.round(rtt)} ms`)
        this.element.textContent = text.join('  ·  ')
    }

    destroy() { this.show(false); this.element.remove() }
}
