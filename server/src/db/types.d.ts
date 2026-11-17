// Tables de la base, pour Kysely (vérifiées par l'éditeur dans les fichiers JS via les commentaires JSDoc).
// À tenir à jour avec les migrations (migrations/).
export interface DB {
  users: {
    id: number
    google_sub: string
    email: string | null
    name: string
    created_at: Date
  }

  sessions: {
    token_hash: string
    user_id: number
    created_at: Date
    last_used_at: Date
  }

  teams: {
    id: string
    name: string
    owner_id: number
    created_at: Date
  }

  team_members: {
    team_id: string
    user_id: number
    joined_at: Date
  }

  invites: {
    code: string
    team_id: string
    created_by: number
    expires_at: Date
  }

  /** Éléments synchronisés ; space : « u:<id utilisateur> » (perso) ou « t:<id équipe> ». */
  items: {
    space: string
    kind: string
    item_id: string
    updated_at: number
    deleted: number | boolean
    json: string | null
    seq: number
  }

  counters: {
    name: string
    value: number
  }
}
