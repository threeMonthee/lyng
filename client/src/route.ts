import { useEffect, useState } from "react";

export type Route =
  | { name: "home" }
  | { name: "create" }
  | { name: "new"; query: URLSearchParams }
  | { name: "scene"; id: string }
  | { name: "character"; id: string }
  | { name: "settings" };

export function parseRoute(hash: string): Route {
  const [path, query = ""] = hash.replace(/^#/, "").split("?");
  const parts = path.split("/").filter(Boolean);
  switch (parts[0]) {
    case "create":
      return { name: "create" };
    case "new":
      return { name: "new", query: new URLSearchParams(query) };
    case "scene":
      return parts[1] ? { name: "scene", id: parts[1] } : { name: "home" };
    case "character":
      return parts[1] ? { name: "character", id: parts[1] } : { name: "home" };
    case "settings":
      return { name: "settings" };
    default:
      return { name: "home" };
  }
}

export function useRoute(): Route {
  const [route, setRoute] = useState(() => parseRoute(location.hash));
  useEffect(() => {
    const onChange = () => setRoute(parseRoute(location.hash));
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);
  return route;
}

export function go(hash: string) {
  location.hash = hash;
}
