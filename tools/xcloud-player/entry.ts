// Bannerlator cloud player page: runs one Xbox Cloud Gaming session. Every request to Microsoft goes
// through the app (BLCloud), which holds the tokens; this page only does WebRTC, video, sound and input.
import xCloudPlayer from './greenlight/lib/player'
import Gamepad from './greenlight/lib/input/gamepad'

interface Reply { status: number, body: string }
declare const BLCloud: { call(id: number, op: string, arg: string): void, status(text: string): void, failed(stage: string, detail: string): void, playing(): void }

const pending = new Map<number, (reply: Reply) => void>()
let nextId = 1
;(window as any).__blResolve = (id: number, status: number, body: string) => {
    const done = pending.get(id)
    pending.delete(id)
    if (done) done({ status, body })
}

function call(op: string, arg: unknown = ''): Promise<Reply> {
    return new Promise(resolve => {
        const id = nextId++
        pending.set(id, resolve)
        BLCloud.call(id, op, typeof arg === 'string' ? arg : JSON.stringify(arg))
    })
}

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms))
const ok = (reply: Reply) => reply.status >= 200 && reply.status < 300

function show(text: string) {
    const element = document.getElementById('status')
    if (element) element.textContent = text
    BLCloud.status(text)
}

class Failure extends Error {
    constructor(public stage: string, public detail: string) { super(stage) }
}

function expect(stage: string, reply: Reply): Reply {
    if (!ok(reply)) throw new Failure(stage, `${reply.status} ${reply.body.slice(0, 300)}`)
    return reply
}

function waitForCandidates(pc: RTCPeerConnection, timeout: number): Promise<void> {
    return new Promise(resolve => {
        if (pc.iceGatheringState === 'complete') return resolve()
        const timer = setTimeout(resolve, timeout)
        pc.addEventListener('icegatheringstatechange', () => {
            if (pc.iceGatheringState === 'complete') { clearTimeout(timer); resolve() }
        })
    })
}

async function run() {
    show('Starting the game on Xbox Cloud Gaming…')
    expect('start', await call('start'))

    let connected = false
    let waited = 0
    for (;;) {
        const reply = expect('state', await call('state'))
        const state = JSON.parse(reply.body || '{}')
        if (state.state === 'Provisioned') break
        if (state.state === 'Failed') throw new Failure('provision', JSON.stringify(state.errorDetails ?? {}).slice(0, 300))
        if (state.state === 'ReadyToConnect' && !connected) {
            connected = true
            show('Signing in to the cloud console…')
            expect('connect', await call('connect'))
        } else if (state.state === 'WaitingForResources') {
            show(`In the queue for a cloud console… ${Math.round(waited / 1000)} s`)
        } else {
            show(`Getting a cloud console ready… ${Math.round(waited / 1000)} s`)
        }
        await sleep(1000)
        waited += 1000
    }

    show('Connecting…')
    const player = new xCloudPlayer('player')
    player.init()
    const pc = player._peerConnection
    pc.addEventListener('connectionstatechange', () => {
        if (pc.connectionState === 'connected') { show(''); BLCloud.playing() }
        else if (pc.connectionState === 'failed') BLCloud.failed('webrtc', 'connection failed')
        else if (pc.connectionState === 'disconnected') show('Connection lost…')
    })

    const offer = await player.createOffer()
    const sdp = expect('sdp', await call('sdp', offer.sdp ?? ''))
    const answer = JSON.parse(JSON.parse(sdp.body).exchangeResponse)
    if (answer.status && answer.status !== 'success') throw new Failure('sdp-answer', JSON.stringify(answer).slice(0, 300))
    await player.setRemoteSDP(answer.sdp)

    await waitForCandidates(pc, 3000)
    const local = player.getICECandidates().map(c => JSON.stringify({
        candidate: c.candidate, sdpMid: c.sdpMid, sdpMLineIndex: c.sdpMLineIndex, usernameFragment: c.usernameFragment,
    }))
    const ice = expect('ice', await call('ice', local))
    player.setRemoteIceCandidates(JSON.parse(JSON.parse(ice.body).exchangeResponse))

    const pad = new Gamepad(0, { enable_keyboard: false })
    pad.attach(player)

    setInterval(() => { call('keepalive') }, 30_000)
    ;(window as any).__blStop = () => { try { pad.detach(); player.destroy() } catch (e) { /* closing anyway */ } }
}

run().catch((error: unknown) => {
    if (error instanceof Failure) BLCloud.failed(error.stage, error.detail)
    else BLCloud.failed('page', String(error).slice(0, 300))
})
