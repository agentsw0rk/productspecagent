import { create } from "zustand";
import { authLogin, authLogout, authMe, authRegister, type AuthMe } from "@/lib/api";
import { clearAuthToken, getAuthToken, setAuthToken } from "@/lib/auth-token";

type AuthStatus = "loading" | "authenticated" | "guest";

interface AuthState {
  user: AuthMe | null;
  status: AuthStatus;

  initialize: () => Promise<void>;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  clear: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  status: "loading",

  initialize: async () => {
    const token = getAuthToken();
    if (!token) {
      set({ user: null, status: "guest" });
      return;
    }
    try {
      const user = await authMe();
      if (getAuthToken() !== token) return;
      set({ user, status: "authenticated" });
    } catch {
      if (getAuthToken() === token) {
        clearAuthToken();
        set({ user: null, status: "guest" });
      }
    }
  },

  login: async (email, password) => {
    const { token, ...user } = await authLogin(email, password);
    setAuthToken(token);
    set({ user, status: "authenticated" });
  },

  register: async (email, password) => {
    const { token, ...user } = await authRegister(email, password);
    setAuthToken(token);
    set({ user, status: "authenticated" });
  },

  logout: async () => {
    try {
      await authLogout();
    } finally {
      clearAuthToken();
      set({ user: null, status: "guest" });
    }
  },

  clear: () => {
    clearAuthToken();
    set({ user: null, status: "guest" });
  },
}));
