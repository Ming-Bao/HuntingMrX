package com.huntingmrxwellington.dto;

import java.util.List;

public class ValidMoveDTO {
    private int nodeId;
    private List<String> ticketOptions;

    public ValidMoveDTO() {}

    public ValidMoveDTO(int nodeId, List<String> ticketOptions) {
        this.nodeId = nodeId;
        this.ticketOptions = ticketOptions;
    }

    public int getNodeId()                          { return nodeId; }
    public void setNodeId(int nodeId)               { this.nodeId = nodeId; }

    public List<String> getTicketOptions()          { return ticketOptions; }
    public void setTicketOptions(List<String> opts) { this.ticketOptions = opts; }
}
