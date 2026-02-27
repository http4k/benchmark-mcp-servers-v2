import cluster from 'cluster';
import os from 'os';
import express from 'express';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { z } from 'zod';
import Redis from 'ioredis';

const API_SERVICE_URL = process.env.API_SERVICE_URL || 'http://mcp-api-service:8100';
const REDIS_URL = process.env.REDIS_URL || 'redis://mcp-redis:6379';
const SERVER_TYPE = process.env.SERVER_TYPE || 'nodejs';

let redis = null;

// Cluster mode
if (cluster.isPrimary) {
    const numWorkers = parseInt(process.env.WEB_CONCURRENCY || os.cpus().length, 10);
    for (let i = 0; i < numWorkers; i++) cluster.fork();
    cluster.on('exit', () => cluster.fork());
} else {
    startServer();
}

function getRedis() {
    return new Redis(REDIS_URL, { lazyConnect: false, enableReadyCheck: false, maxRetriesPerRequest: 3 });
}

function createMcpServer(redisClient) {
    const server = new McpServer({ name: 'BenchmarkNodejsServer', version: '1.0.0' });

    server.tool(
        'search_products',
        'Search products by category and price range, merged with popularity data',
        {
            category: z.string().default('Electronics'),
            min_price: z.number().default(50.0),
            max_price: z.number().default(500.0),
            limit: z.number().default(10),
        },
        async ({ category, min_price, max_price, limit }) => {
            const params = new URLSearchParams({ category, min_price, max_price, limit });
            const [searchResp, popularRaw] = await Promise.all([
                fetch(`${API_SERVICE_URL}/products/search?${params}`).then(r => r.json()),
                redisClient.zrevrange('bench:popular', 0, 9),
            ]);
            const top10Ids = popularRaw.map(m => parseInt(m.split(':')[1], 10));
            const top10Set = Object.fromEntries(top10Ids.map((id, i) => [id, i + 1]));
            const products = (searchResp.products || []).map(p => ({
                id: p.id, sku: p.sku, name: p.name,
                price: p.price, rating: p.rating,
                popularity_rank: top10Set[p.id] || 0,
            }));
            return {
                content: [{
                    type: 'text',
                    text: JSON.stringify({
                        category, total_found: searchResp.total_found || 0,
                        products, top10_popular_ids: top10Ids,
                        server_type: SERVER_TYPE,
                    }),
                }],
            };
        }
    );

    server.tool(
        'get_user_cart',
        'Get user cart details with recent order history',
        { user_id: z.string().default('user-00042') },
        async ({ user_id }) => {
            const cartHash = await redisClient.hgetall(`bench:cart:${user_id}`);
            const items = cartHash?.items ? JSON.parse(cartHash.items) : [];
            const firstProductId = items[0]?.product_id || 1;
            const [productResp, historyRaw] = await Promise.all([
                fetch(`${API_SERVICE_URL}/products/${firstProductId}`).then(r => r.json()),
                redisClient.lrange(`bench:history:${user_id}`, 0, 4),
            ]);
            const recentHistory = historyRaw.map(entry => { try { return JSON.parse(entry); } catch { return { raw: entry }; } });
            const estimatedTotal = parseFloat(cartHash?.total || '0');
            return {
                content: [{
                    type: 'text',
                    text: JSON.stringify({
                        user_id,
                        cart: { items, item_count: items.length, estimated_total: estimatedTotal },
                        recent_history: recentHistory,
                        server_type: SERVER_TYPE,
                    }),
                }],
            };
        }
    );

    server.tool(
        'checkout',
        'Process checkout: calculate total, update rate limit, record history',
        {
            user_id: z.string().default('user-00042'),
            items: z.array(z.object({ product_id: z.number(), quantity: z.number() }))
                .default([{ product_id: 42, quantity: 2 }, { product_id: 1337, quantity: 1 }]),
        },
        async ({ user_id, items }) => {
            const userNum = parseInt(user_id.split('-').pop() || '42', 10);
            const rateKey = `bench:ratelimit:user-${String(userNum % 100).padStart(5, '0')}`;
            const histKey = `bench:history:${user_id}`;
            const productId = items[0]?.product_id || 1;
            const orderEntry = JSON.stringify({ order_id: `ORD-${user_id}-${Date.now()}`, items, ts: Math.floor(Date.now() / 1000) });
            const [calcResp, rateCount] = await Promise.all([
                fetch(`${API_SERVICE_URL}/cart/calculate`, {
                    method: 'POST', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ user_id, items }),
                }).then(r => r.json()),
                redisClient.incr(rateKey),
                redisClient.rpush(histKey, orderEntry),
                redisClient.zincrby('bench:popular', 1, `product:${productId}`),
            ]);
            return {
                content: [{
                    type: 'text',
                    text: JSON.stringify({
                        order_id: calcResp.order_id || `ORD-${user_id}-${Date.now()}`,
                        user_id, total: calcResp.total || 0,
                        items_count: items.length, rate_limit_count: rateCount,
                        status: 'confirmed', server_type: SERVER_TYPE,
                    }),
                }],
            };
        }
    );

    return server;
}

function startServer() {
    redis = getRedis();
    const app = express();
    app.use(express.json());

    app.post('/mcp', async (req, res) => {
        const mcpServer = createMcpServer(redis);
        const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined });
        try {
            await mcpServer.connect(transport);
            await transport.handleRequest(req, res, req.body);
        } catch (error) {
            console.error('Error handling MCP request:', error);
            if (!res.headersSent) res.status(500).json({ error: error.message });
        } finally {
            transport.close();
            mcpServer.close();
        }
    });

    app.get('/mcp', (req, res) => res.status(405).end());
    app.delete('/mcp', (req, res) => res.status(405).end());
    app.get('/health', (req, res) => res.json({ status: 'ok', server_type: SERVER_TYPE }));

    const PORT = 8083;
    app.listen(PORT, () => console.log(`Node.js MCP worker ${process.pid} listening on port ${PORT}`));
}
