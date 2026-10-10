import { useEffect, useState } from "react";

/** 极简的 hash 路由：#/scene/ID、#/new?world=ID、#/create、#/character/ID、#/world/ID、#/usage。 */
export type Route = { path: string[]; query: URLSearchParams };

function read(): Route {
  const hash = window.location.hash.replace(/^#/, "") || "/";
  const [path, query = ""] = hash.split("?");
  return { path: path.split("/").filter(Boolean), query: new URLSearchParams(query) };
}

export function useRoute(): Route {
  const [route, setRoute] = useState(read);
  useEffect(() => {
    const onChange = () => setRoute(read());
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);
  return route;
}

export function go(hash: string) {
  window.location.hash = hash;
}
