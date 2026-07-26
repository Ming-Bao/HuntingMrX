package com.huntingmrxwellington.dto;

import java.util.List;

public class ValidMovesDTO {
    private List<ValidMoveDTO> moves;

    public ValidMovesDTO() {}
    public ValidMovesDTO(List<ValidMoveDTO> moves) { this.moves = moves; }

    public List<ValidMoveDTO> getMoves()           { return moves; }
    public void setMoves(List<ValidMoveDTO> moves) { this.moves = moves; }
}
