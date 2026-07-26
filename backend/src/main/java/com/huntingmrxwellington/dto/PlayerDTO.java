package com.huntingmrxwellington.dto;

import com.huntingmrxwellington.model.Role;
import com.huntingmrxwellington.model.TicketType;
import java.util.Map;

public class PlayerDTO {
    private String id;
    private String name;
    private Role role;
    private Integer nodeId;
    private Map<TicketType, Integer> tickets;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public Integer getNodeId() { return nodeId; }
    public void setNodeId(Integer nodeId) { this.nodeId = nodeId; }

    public Map<TicketType, Integer> getTickets() { return tickets; }
    public void setTickets(Map<TicketType, Integer> tickets) { this.tickets = tickets; }
}
