import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

// ─── Configuration ───────────────────────────────────────────────────
const SERVER_URL = __ENV.SERVER_URL || 'http://localhost:8080/mcp';
const SERVER_NAME = __ENV.SERVER_NAME || 'unknown';

export const options = {
    stages: [
        { duration: '15s', target: 50 },   // ramp-up
        { duration: '5m', target: 50 },   // sustained load
        { duration: '10s', target: 0 },   // ramp-down
    ],
    thresholds: {
        'http_req_failed': ['rate<0.05'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'],
};

// ─── Warmup ──────────────────────────────────────────────────────────
// 60s exclusion: enough for JVM JIT to reach peak optimization on all tool paths.
const WARMUP_SECONDS = 60;

export function setup() {
    return { startMs: Date.now() };
}

// ─── Custom Metrics ──────────────────────────────────────────────────
const initDuration = new Trend('mcp_initialize_duration', true);
const toolsListDuration = new Trend('mcp_tools_list_duration', true);
const searchProductsDuration = new Trend('mcp_search_products_duration', true);
const getUserCartDuration = new Trend('mcp_get_user_cart_duration', true);
const checkoutDuration = new Trend('mcp_checkout_duration', true);
const sessionDuration = new Trend('mcp_full_session_duration', true);
const mcpErrors = new Counter('mcp_errors');
const mcpRequests = new Counter('mcp_requests');
const mcpErrorRate = new Rate('mcp_error_rate');

// ─── Helpers ─────────────────────────────────────────────────────────
const BASE_HEADERS = {
    'Content-Type': 'application/json',
    'Accept': 'application/json, text/event-stream',
};

function parseBody(body) {
    /** Parse response body — handles JSON and SSE formats. */
    if (!body) return null;
    const text = body.trim();
    if (text.length === 0) return null;

    if (text.startsWith('{') || text.startsWith('[')) {
        try { return JSON.parse(text); } catch (e) { /* fall through */ }
    }

    // SSE format — find data: line with JSON
    const lines = text.split('\n');
    for (const line of lines) {
        const trimmed = line.trim();
        if (trimmed.startsWith('data:')) {
            const payload = trimmed.substring(5).trim();
            if (payload && payload.startsWith('{')) {
                try { return JSON.parse(payload); } catch (e) { /* continue */ }
            }
        }
    }
    return null;
}

function mcpPost(payload, sessionId) {
    /** Send JSON-RPC request with optional session ID. */
    const headers = Object.assign({}, BASE_HEADERS);
    if (sessionId) {
        headers['Mcp-Session-Id'] = sessionId;
    }
    const res = http.post(SERVER_URL, JSON.stringify(payload), {
        headers: headers,
        timeout: '30s',
    });
    mcpRequests.add(1);

    const newSessionId = res.headers['Mcp-Session-Id'] || sessionId;
    const result = parseBody(res.body);

    return { res, result, sessionId: newSessionId };
}

function mcpSession(toolName, toolArgs) {
    /**
     * Execute a complete MCP session:
     * 1. initialize → get session ID
     * 2. notifications/initialized
     * 3. tools/call
     * 4. DELETE session (cleanup)
     */
    const start = Date.now();

    // 1. Initialize
    const { res: initRes, result: initResult, sessionId } = mcpPost({
        jsonrpc: '2.0', id: 1, method: 'initialize',
        params: {
            protocolVersion: '2024-11-05',
            capabilities: {},
            clientInfo: { name: 'k6-bench', version: '1.0' },
        },
    }, null);

    // 2. notifications/initialized (protocol overhead — não conta no RPS)
    const notifyHeaders = Object.assign({}, BASE_HEADERS);
    if (sessionId) notifyHeaders['Mcp-Session-Id'] = sessionId;
    http.post(SERVER_URL, JSON.stringify({
        jsonrpc: '2.0', method: 'notifications/initialized',
    }), { headers: notifyHeaders, timeout: '5s', tags: { name: 'protocol_overhead' } });

    // 3. Tool call
    const { res: callRes, result: callResult } = mcpPost({
        jsonrpc: '2.0', id: 2, method: 'tools/call',
        params: { name: toolName, arguments: toolArgs },
    }, sessionId);

    const totalDuration = Date.now() - start;

    // 4. Close session (protocol overhead — não conta no RPS)
    if (sessionId) {
        const delHeaders = { 'Mcp-Session-Id': sessionId };
        http.del(SERVER_URL, null, { headers: delHeaders, timeout: '2s', tags: { name: 'protocol_overhead' } });
    }

    const isError = !callResult || callResult.error || !callResult.result;
    if (isError) {
        mcpErrors.add(1);
        mcpErrorRate.add(true);
    } else {
        mcpErrorRate.add(false);
    }

    return { initRes, callRes, callResult, totalDuration, sessionId };
}

// ─── Tool definitions ────────────────────────────────────────────────
// Tools are built per-VU so that user_id is distributed across 1000 seeded users.
// __VU ranges 1–50, giving user-00001 to user-00050 — all have carts and histories seeded.
function makeTools(userId) {
    return [
        {
            name: 'search_products',
            args: { category: 'Electronics', min_price: 50.0, max_price: 500.0, limit: 10 },
            metric: searchProductsDuration,
            checkName: 'search_products ok',
            check: (r) => {
                if (!r?.result?.content) return false;
                try {
                    const d = JSON.parse(r.result.content[0].text);
                    return d.total_found === 2251 && d.products.length === 10 && d.top10_popular_ids.length === 10;
                } catch { return false; }
            },
        },
        {
            name: 'get_user_cart',
            args: { user_id: userId },
            metric: getUserCartDuration,
            checkName: 'get_user_cart ok',
            check: (r) => {
                if (!r?.result?.content) return false;
                try {
                    const d = JSON.parse(r.result.content[0].text);
                    return !!d.user_id && d.cart.items.length >= 1 && d.recent_history.length === 5;
                } catch { return false; }
            },
        },
        {
            name: 'checkout',
            args: {
                user_id: userId,
                items: [{ product_id: 42, quantity: 2 }, { product_id: 1337, quantity: 1 }],
            },
            metric: checkoutDuration,
            checkName: 'checkout ok',
            check: (r) => {
                if (!r?.result?.content) return false;
                try {
                    const d = JSON.parse(r.result.content[0].text);
                    return d.status === 'confirmed' && d.total > 0 && d.items_count === 2;
                } catch { return false; }
            },
        },
    ];
}

// ─── Main Test Function ──────────────────────────────────────────────
export default function (setupData) {
    const pastWarmup = (Date.now() - setupData.startMs) > WARMUP_SECONDS * 1000;

    // Each VU uses its own user — distributes Redis key load across 1000 seeded users
    const userId = `user-${String(__VU % 1000).padStart(5, '0')}`;
    const TOOLS = makeTools(userId);

    for (const tool of TOOLS) {
        const { initRes, callRes, callResult, totalDuration } = mcpSession(tool.name, tool.args);

        // Only record to custom metrics after warmup period
        if (pastWarmup) {
            initDuration.add(initRes.timings.duration);
            tool.metric.add(callRes.timings.duration);
            sessionDuration.add(totalDuration);
        }

        const checks = {};
        checks[tool.checkName] = tool.check;
        check(callResult, checks);
    }

    // Also benchmark tools/list in its own session
    {
        const { res: initRes, sessionId } = mcpPost({
            jsonrpc: '2.0', id: 1, method: 'initialize',
            params: {
                protocolVersion: '2024-11-05', capabilities: {},
                clientInfo: { name: 'k6-bench', version: '1.0' },
            },
        }, null);

        const notifyHeaders2 = Object.assign({}, BASE_HEADERS);
        if (sessionId) notifyHeaders2['Mcp-Session-Id'] = sessionId;
        http.post(SERVER_URL, JSON.stringify({
            jsonrpc: '2.0', method: 'notifications/initialized',
        }), { headers: notifyHeaders2, timeout: '5s', tags: { name: 'protocol_overhead' } });

        const { res: listRes, result: listResult } = mcpPost({
            jsonrpc: '2.0', id: 2, method: 'tools/list', params: {},
        }, sessionId);

        if (pastWarmup) {
            toolsListDuration.add(listRes.timings.duration);
        }
        check(listResult, { 'tools/list ok': (r) => r && r.result && r.result.tools });

        if (sessionId) {
            http.del(SERVER_URL, null, { headers: { 'Mcp-Session-Id': sessionId }, timeout: '2s', tags: { name: 'protocol_overhead' } });
        }
    }

    sleep(0.05);
}

// ─── Custom Summary ──────────────────────────────────────────────────
export function handleSummary(data) {
    const outputPath = __ENV.OUTPUT_PATH || `results/${SERVER_NAME}_k6.json`;

    const summary = {
        server: SERVER_NAME,
        timestamp: new Date().toISOString(),
        config: { vus: 50, duration: '5m', warmup_seconds: WARMUP_SECONDS, server_url: SERVER_URL },
        http: {
            // RPS baseado em mcp_requests (normalize: exclui notifications/initialized e DELETE session)
            // Conta apenas requests com trabalho real: initialize + tools/call + tools/list
            total_requests: getValue(data, 'mcp_requests', 'count', 0),
            failed_requests: getValue(data, 'http_req_failed', 'passes', 0),
            rps: getValue(data, 'mcp_requests', 'rate', 0),
            // Latency from tool-level metrics (excludes protocol overhead)
            latency: computeToolLatency(data),
            // Raw http_reqs para referencia (inclui notification + DELETE overhead)
            raw_http_reqs: getValue(data, 'http_reqs', 'count', 0),
            raw_rps: getValue(data, 'http_reqs', 'rate', 0),
        },
        mcp: {
            total_mcp_requests: getValue(data, 'mcp_requests', 'count', 0),
            mcp_errors: getValue(data, 'mcp_errors', 'count', 0),
            error_rate: getValue(data, 'mcp_error_rate', 'rate', 0),
        },
        session: extractTrend(data, 'mcp_full_session_duration'),
        tools: {},
    };

    const toolMetrics = {
        'search_products': 'mcp_search_products_duration',
        'get_user_cart': 'mcp_get_user_cart_duration',
        'checkout': 'mcp_checkout_duration',
        '_initialize': 'mcp_initialize_duration',
        '_tools_list': 'mcp_tools_list_duration',
    };

    for (const [toolName, metricName] of Object.entries(toolMetrics)) {
        const t = extractTrend(data, metricName);
        if (t) summary.tools[toolName] = t;
    }

    return {
        [outputPath]: JSON.stringify(summary, null, 2),
        stdout: textSummary(data, { indent: '  ', enableColors: true }),
    };
}

function getValue(data, metric, key, fallback) {
    try { return data.metrics[metric].values[key]; } catch { return fallback; }
}

function extractTrend(data, name) {
    try {
        const m = data.metrics[name];
        if (!m) return null;
        return {
            avg: m.values.avg, min: m.values.min, max: m.values.max,
            p50: m.values.med, p90: m.values['p(90)'],
            p95: m.values['p(95)'], p99: m.values['p(99)'],
            count: m.values.count,
        };
    } catch { return null; }
}

function computeToolLatency(data) {
    // Compute latency using count-weighted averages across tool metrics.
    // Weighting by count gives the correct aggregate: a tool called 100k times
    // contributes proportionally more than one called 10k times.
    // This avoids the bias of simple arithmetic mean of unequal-count distributions.
    const toolMetricNames = [
        'mcp_search_products_duration',
        'mcp_get_user_cart_duration',
        'mcp_checkout_duration',
    ];

    const entries = [];
    for (const name of toolMetricNames) {
        try {
            const m = data.metrics[name];
            if (!m || m.values.avg === undefined) continue;
            entries.push({
                avg: m.values.avg,
                min: m.values.min,
                max: m.values.max,
                p50: m.values.med,
                p90: m.values['p(90)'],
                p95: m.values['p(95)'],
                p99: m.values['p(99)'],
                count: m.values.count || 1,
            });
        } catch { /* skip */ }
    }

    if (entries.length === 0) {
        return { avg: null, min: null, max: null, p50: null, p90: null, p95: null, p99: null };
    }

    const totalCount = entries.reduce((s, e) => s + e.count, 0);
    const weightedAvg = (key) =>
        entries.reduce((s, e) => s + e[key] * e.count, 0) / totalCount;

    return {
        avg: weightedAvg('avg'),
        min: Math.min(...entries.map(e => e.min)),
        max: Math.max(...entries.map(e => e.max)),
        p50: weightedAvg('p50'),
        p90: weightedAvg('p90'),
        p95: weightedAvg('p95'),
        p99: weightedAvg('p99'),
    };
}
