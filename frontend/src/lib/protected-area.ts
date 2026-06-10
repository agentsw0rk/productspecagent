import { ApiError } from "@/lib/api";

export function isProtectedAreaError(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 401 || error.status === 403);
}
