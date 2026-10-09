import { useEffect, useState } from "react";
import { api, setUnauthorizedHandler } from "./api";
import { CharacterPage } from "./pages/CharacterPage";
import { CreatePage } from "./pages/CreatePage";
import { HomePage } from "./pages/HomePage";
import { LoginPage } from "./pages/LoginPage";
import { NewScenePage } from "./pages/NewScenePage";
import { ScenePage } from "./pages/ScenePage";
import { SettingsPage } from "./pages/SettingsPage";
import { useRoute } from "./route";

export function App() {
  const route = useRoute();
  const [user, setUser] = useState<string | null | undefined>(undefined);

  useEffect(() => {
    setUnauthorizedHandler(() => setUser(null));
    api<{ name: string }>("/me")
      .then((me) => setUser(me.name))
      .catch(() => setUser(null));
  }, []);

  if (user === undefined) return <div className="center-note">…</div>;
  if (user === null) return <LoginPage onLogin={setUser} />;

  const logout = () => api("/logout", "POST").finally(() => setUser(null));
  switch (route.name) {
    case "create":
      return <CreatePage />;
    case "new":
      return <NewScenePage query={route.query} />;
    case "scene":
      return <ScenePage key={route.id} id={route.id} />;
    case "character":
      return <CharacterPage id={route.id} />;
    case "settings":
      return <SettingsPage onLogout={logout} />;
    default:
      return <HomePage />;
  }
}
