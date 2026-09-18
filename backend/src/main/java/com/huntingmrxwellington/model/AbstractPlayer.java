package com.huntingmrxwellington.model;

/** Shared id/name/nodeId for every player kind (lobby, detective, Mr X) —
 *  only ticket handling differs between them, so that's all subclasses own. */
public abstract class AbstractPlayer implements Player {

    private final String id;
    private final String name;
    private Integer nodeId;

    protected AbstractPlayer(String id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override public String getId() { return id; }
    @Override public String getName() { return name; }
    @Override public Integer getNodeId() { return nodeId; }
    @Override public void setNodeId(Integer nodeId) { this.nodeId = nodeId; }
}
