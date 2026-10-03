import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { logger } from "../util/log.ts";

const log = logger("auth");

export interface Profile {
  id: string;
  name: string;
}

export interface Session {
  apiRoot: string;
  clientToken: string;
  accessToken: string;
  profile: Profile;
}

interface Credentials {
  username: string;
  password: () => string;
  profile?: string;
}

class YggdrasilError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function post(apiRoot: string, endpoint: string, body: unknown): Promise<unknown> {
  const res = await fetch(`${apiRoot.replace(/\/$/, "")}/authserver/${endpoint}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  if (res.status === 204) return null;
  const text = await res.text();
  if (!res.ok) {
    let message = text;
    try {
      const j = JSON.parse(text) as { errorMessage?: string; error?: string };
      message = j.errorMessage ?? j.error ?? text;
    } catch {
      // keep raw body
    }
    throw new YggdrasilError(res.status, `${endpoint}: HTTP ${res.status} ${message}`);
  }
  return text ? JSON.parse(text) : null;
}

interface AuthResponse {
  accessToken: string;
  clientToken: string;
  selectedProfile?: Profile;
  availableProfiles?: Profile[];
}

/**
 * Returns a valid session, reusing the cached token when possible
 * (validate → refresh → authenticate). The cache lives in data/<bot>/auth.json.
 */
export async function obtainSession(apiRoot: string, cacheFile: string, creds: Credentials): Promise<Session> {
  const cached = readCache(cacheFile, apiRoot);
  if (cached) {
    try {
      await post(apiRoot, "validate", { accessToken: cached.accessToken, clientToken: cached.clientToken });
      log.info(`Cached token valid for ${cached.profile.name}`);
      return cached;
    } catch (e) {
      log.info(`Cached token invalid (${(e as Error).message}); refreshing`);
    }
    try {
      const r = (await post(apiRoot, "refresh", {
        accessToken: cached.accessToken,
        clientToken: cached.clientToken,
      })) as AuthResponse;
      const session = { apiRoot, clientToken: r.clientToken, accessToken: r.accessToken, profile: r.selectedProfile ?? cached.profile };
      writeCache(cacheFile, session);
      log.info(`Refreshed token for ${session.profile.name}`);
      return session;
    } catch (e) {
      log.info(`Refresh failed (${(e as Error).message}); re-authenticating`);
    }
  }

  const clientToken = cached?.clientToken ?? crypto.randomUUID().replaceAll("-", "");
  let r = (await post(apiRoot, "authenticate", {
    agent: { name: "Minecraft", version: 1 },
    username: creds.username,
    password: creds.password(),
    clientToken,
    requestUser: false,
  })) as AuthResponse;

  let profile = r.selectedProfile;
  const wanted = creds.profile;
  if (!profile || (wanted && profile.name !== wanted)) {
    const candidates = r.availableProfiles ?? [];
    const pick = wanted ? candidates.find((p) => p.name === wanted) : candidates[0];
    if (!pick) {
      const names = candidates.map((p) => p.name).join(", ") || "(none)";
      throw new Error(`No usable profile${wanted ? ` named ${wanted}` : ""}; account has: ${names}`);
    }
    r = (await post(apiRoot, "refresh", {
      accessToken: r.accessToken,
      clientToken: r.clientToken,
      selectedProfile: pick,
    })) as AuthResponse;
    profile = r.selectedProfile ?? pick;
  }

  const session: Session = { apiRoot, clientToken: r.clientToken, accessToken: r.accessToken, profile };
  writeCache(cacheFile, session);
  log.info(`Authenticated as ${profile.name} (${profile.id})`);
  return session;
}

/** Base64 of the API metadata, for -Dauthlibinjector.yggdrasil.prefetched. */
export async function prefetchMetadata(apiRoot: string): Promise<string> {
  const res = await fetch(apiRoot);
  if (!res.ok) throw new Error(`Failed to fetch Yggdrasil metadata: HTTP ${res.status}`);
  return Buffer.from(await res.text(), "utf8").toString("base64");
}

function readCache(file: string, apiRoot: string): Session | null {
  try {
    const s = JSON.parse(fs.readFileSync(file, "utf8")) as Session;
    return s.apiRoot === apiRoot && s.accessToken && s.profile ? s : null;
  } catch {
    return null;
  }
}

function writeCache(file: string, session: Session): void {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(session, null, 2), { mode: 0o600 });
}
