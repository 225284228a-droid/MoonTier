package cn.moonflow.easytier

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NodeSnapshotParserTest {
    @Test
    fun firstRttReplacesUnknownWithoutDisplayingRouteEstimateAndReconnectDropsOldSample() {
        val snapshot = JSONObject("""{
            "routes":[{"peer_id":1,"cost":1,"path_latency":1000}],
            "peers":[{"peer_id":1,"conns":[{"conn_id":"first","stats":{"latency_us":0}}]}]
        }""")
        val connection = snapshot.getJSONArray("peers").getJSONObject(0)
            .getJSONArray("conns").getJSONObject(0)
        assertEquals(-1, parseNodes(snapshot).single().latencyMs)
        connection.getJSONObject("stats").put("latency_us", 12000)
        assertEquals(12, parseNodes(snapshot).single().latencyMs)
        connection.put("is_closed", true)
        assertEquals(-1, parseNodes(snapshot).single().latencyMs)
        connection.put("is_closed", false).put("conn_id", "reconnected")
        connection.getJSONObject("stats").put("latency_us", 0)
        assertEquals(-1, parseNodes(snapshot).single().latencyMs)
        connection.getJSONObject("stats").put("latency_us", 8001)
        assertEquals(9, parseNodes(snapshot).single().latencyMs)
    }

    @Test
    fun realHighRttSamplesAreNotFilteredAsEstimates() {
        for (latencyMs in listOf(500, 1000, 2500)) {
            val snapshot = JSONObject("""{
                "routes":[{"peer_id":1,"cost":1,"path_latency":1}],
                "peers":[{"peer_id":1,"conns":[{"stats":{"latency_us":${latencyMs * 1000}}}]}]
            }""")
            assertEquals(latencyMs, parseNodes(snapshot).single().latencyMs)
        }
    }

    @Test
    fun pairsUseTheirOwnPeerAndPreferActiveDefaultConnection() {
        val nodes = parseNodes(JSONObject("""
            {
              "my_node_info":{"hostname":"local","virtual_ipv4":{"address":{"addr":167772161},"network_length":24}},
              "peer_route_pairs":[
                {"route":{"peer_id":7,"hostname":"relay","cost":2,"path_latency":999,"ipv4_addr":{"address":{"addr":167772162}}},
                 "peer":{"peer_id":7,"default_conn_id":"chosen","conns":[
                   {"conn_id":"fast","stats":{"latency_us":500}},
                   {"conn_id":"chosen","stats":{"latency_us":2501}},
                   {"conn_id":"closed","is_closed":true,"stats":{"latency_us":1}}]}},
                {"route":{"peer_id":8,"hostname":"server","cost":1,"feature_flag":{"is_public_server":true}},
                 "peer":{"peer_id":8,"conns":[{"stats":{"latency_us":1000}}]}}
              ]
            }
        """.trimIndent()))

        assertEquals(3, nodes.size)
        assertEquals(0, nodes[0].latencyMs)
        assertEquals("10.0.0.1", nodes[0].ip)
        assertEquals(3, nodes[1].latencyMs)
        assertEquals("中继(2)", nodes[1].role)
        assertEquals(1, nodes[2].latencyMs)
        assertEquals("服务器 · 直连", nodes[2].role)
    }

    @Test
    fun routesMatchPeersByIdAndIgnoreUnavailableSamples() {
        val nodes = parseNodes(JSONObject("""
            {
              "routes":[
                {"peer_id":3,"hostname":"slow","path_latency":50},
                {"peer_id":2,"hostname":"unknown","path_latency":42},
                {"peer_id":4,"hostname":"missing","path_latency":700}
              ],
              "peers":[
                {"peer_id":2,"conns":[{"stats":{"latency_us":0}}, {"stats":{"latency_us":"invalid"}},
                  {"is_closed":true,"stats":{"latency_us":777}}]},
                {"peer_id":3,"default_conn_id":"closed","conns":[
                  {"conn_id":"closed","is_closed":true,"stats":{"latency_us":10}},
                  {"conn_id":"a","stats":{"latency_us":1000000}},
                  {"conn_id":"b","stats":{"latency_us":500000}}]}
              ]
            }
        """.trimIndent()))

        assertEquals(listOf(500, -1, -1), nodes.map { it.latencyMs })
    }

    @Test
    fun protoUuidSelectsDefaultConnection() {
        val nodes = parseNodes(JSONObject("""
            {
              "peer_route_pairs":[{"route":{"peer_id":1,"cost":1},"peer":{
                "default_conn_id":{"part1":305419896,"part2":2427178479,"part3":305419896,"part4":2427178479},
                "conns":[
                  {"conn_id":"other","stats":{"latency_us":1000}},
                  {"conn_id":"12345678-90ab-cdef-1234-567890abcdef","stats":{"latency_us":3200}}
                ]
              }}]
            }
        """.trimIndent()))

        assertEquals(4, nodes.single().latencyMs)
    }

    @Test
    fun unknownCostAndSameAddressPeersRemainDistinct() {
        val nodes = parseNodes(JSONObject("""
            {
              "routes":[
                {"peer_id":1,"hostname":"same","ipv4_addr":{"address":{"addr":167772162}}},
                {"peer_id":2,"hostname":"same","cost":0,"ipv4_addr":{"address":{"addr":167772162}}}
              ]
            }
        """.trimIndent()))

        assertEquals(2, nodes.size)
        assertEquals(listOf("未知", "未知"), nodes.map { it.role })
    }
}
