import type { GameStateDTO, MapData, ValidMoveDTO } from "../types/game";
import { API_BASE } from "../utils/basePath";

// The secret per-player token proves who is acting. The public playerId is only for
// display and turn checks, so knowing someone's id no longer lets you act as them.
const TOKEN_HEADER = "X-Player-Token";

export interface JoinResponse {
    playerId: string;
    playerToken: string;
    gameState: GameStateDTO;
}

async function handleResponse<T>(res: Response): Promise<T> {
    const data = await res.json();
    if (!res.ok) throw new Error(data.error ?? "Request failed");
    return data as T;
}

async function handleNoContent(res: Response): Promise<void> {
    if (!res.ok) {
        const data = await res.json();
        throw new Error(data.error ?? "Request failed");
    }
}

export async function createGame(hostName: string, maxPlayers: number): Promise<JoinResponse> {
    const res = await fetch(`${API_BASE}/games/create`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ hostName, maxPlayers }),
    });
    return handleResponse(res);
}

export async function joinGame(joinCode: string, playerName: string): Promise<JoinResponse> {
    const res = await fetch(`${API_BASE}/games/join`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ joinCode, playerName }),
    });
    return handleResponse(res);
}

export async function getGame(gameId: string, playerToken?: string): Promise<GameStateDTO> {
    const res = await fetch(`${API_BASE}/games/${gameId}`, {
        headers: playerToken ? { [TOKEN_HEADER]: playerToken } : {},
    });
    return handleResponse(res);
}

export async function startGame(gameId: string, playerToken: string): Promise<GameStateDTO> {
    const res = await fetch(`${API_BASE}/games/${gameId}/start`, {
        method: "POST",
        headers: { [TOKEN_HEADER]: playerToken },
    });
    return handleResponse(res);
}

// Removing yourself is leaving; the host removing someone else is a kick.
async function removePlayer(gameId: string, playerToken: string, targetPlayerId: string): Promise<void> {
    const res = await fetch(`${API_BASE}/games/${gameId}/players/${targetPlayerId}`, {
        method: "DELETE",
        headers: { [TOKEN_HEADER]: playerToken },
    });
    return handleNoContent(res);
}

export const leaveGame = removePlayer;
export const kickPlayer = removePlayer;

export async function getValidMoves(gameId: string, playerToken: string): Promise<ValidMoveDTO[]> {
    const res = await fetch(`${API_BASE}/games/${gameId}/valid-moves`, {
        headers: { [TOKEN_HEADER]: playerToken },
    });
    return handleResponse(res);
}

export async function submitMove(
    gameId: string,
    playerToken: string,
    toNodeId: number,
    ticket: string,
): Promise<GameStateDTO> {
    const res = await fetch(`${API_BASE}/games/${gameId}/moves`, {
        method: "POST",
        headers: { "Content-Type": "application/json", [TOKEN_HEADER]: playerToken },
        body: JSON.stringify({ toNodeId, ticket }),
    });
    return handleResponse(res);
}

export async function getMap(): Promise<MapData> {
    const res = await fetch(`${API_BASE}/map`);
    if (!res.ok) throw new Error('Failed to load map data');
    return res.json();
}
