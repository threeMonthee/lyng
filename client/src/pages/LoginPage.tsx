import { useState } from "react";
import { api } from "../api";
import { ErrorLine } from "../ui";

export function LoginPage({ onLogin }: { onLogin: (name: string) => void }) {
  const [name, setName] = useState("me");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const me = await api<{ name: string }>("/login", "POST", { name, password });
      onLogin(me.name);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="login page" onSubmit={submit}>
      <h1 className="login-title">lyng</h1>
      <input aria-label="名字" value={name} onChange={(e) => setName(e.target.value)} autoComplete="username" />
      <input
        aria-label="口令"
        type="password"
        placeholder="口令"
        value={password}
        onChange={(e) => setPassword(e.target.value)}
        autoComplete="current-password"
        autoFocus
      />
      <ErrorLine message={error} />
      <button className="primary" disabled={busy || !password}>
        登录
      </button>
    </form>
  );
}
