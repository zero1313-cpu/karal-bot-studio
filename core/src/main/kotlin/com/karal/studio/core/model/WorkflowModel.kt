package com.karal.studio.core.model

/** A node-based workflow graph. Coordinates are logical canvas units, not pixels. */
data class WorkflowNode(
    val id: String,
    val type: String,
    val group: NodeGroup,
    val params: Map<String, String> = emptyMap(),
    val x: Float = 0f,
    val y: Float = 0f,
    val label: String = ""
)

data class WorkflowEdge(
    val id: String,
    val from: String,
    val to: String,
    val fromPort: String = "out",
    val toPort: String = "in"
)

data class Workflow(
    val nodes: List<WorkflowNode> = emptyList(),
    val edges: List<WorkflowEdge> = emptyList()
) {
    fun triggerNodes(): List<WorkflowNode> = nodes.filter { it.group == NodeGroup.TRIGGER }

    fun outgoing(nodeId: String, port: String = "out"): List<WorkflowEdge> =
        edges.filter { it.from == nodeId && it.fromPort == port }

    fun incoming(nodeId: String): List<WorkflowEdge> = edges.filter { it.to == nodeId }
}
