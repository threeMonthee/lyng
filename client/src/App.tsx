import { useCallback, useEffect, useState } from "react";
import { api } from "./api";
import { CharacterPage } from "./CharacterPage";
import { CreatePage } from "./CreatePage";
import { Home } from "./Home";
import { NewScenePage } from "./NewScenePage";
import { useRoute } from "./route";
import { ScenePage } from "./ScenePage";
import { Sidebar } from "./Sidebar";
import type { Overview } from "./types";
import { UsagePage } from "./UsagePage";
import { WorldPage } from "./WorldPage";

export function App() {
  const route = useRoute();
  const [overview, setOverview] = useState<Overview | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setOverview(await api<Overview>("/overview"));
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const [section, id] = route.path;
  let page;
  switch (section) {
    case "scene":
      page = <ScenePage key={id} id={id} refresh={refresh} />;
      break;
    case "new":
      page = overview && <NewScenePage key={route.query.toString()} overview={overview} query={route.query} />;
      break;
    case "create":
      page = overview && <CreatePage key={route.query.toString()} overview={overview} query={route.query} refresh={refresh} />;
      break;
    case "character":
      page = <CharacterPage key={id} id={id} />;
      break;
    case "world":
      page = <WorldPage key={id} id={id} />;
      break;
    case "usage":
      page = <UsagePage />;
      break;
    default:
      page = overview && <Home overview={overview} />;
  }

  return (
    <div className="app">
      <Sidebar overview={overview} route={route} />
      <main className="main">
        {error && !overview ? (
          <div className="center-state">
            <p>{error}</p>
            <button className="btn" onClick={() => void refresh()}>
              再试一次
            </button>
          </div>
        ) : (
          page
        )}
      </main>
    </div>
  );
}
