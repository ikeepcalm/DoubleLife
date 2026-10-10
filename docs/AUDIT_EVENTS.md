# DoubleLife audit events

Producer `doublelife`, via the shaded Mysterria audit client (relocated to
`dev.ua.ikeepcalm.doublelife.libs.audit`). Rows are spooled to `plugins/mysterria-audit-spool`.
All rows are `STAFF_RESTRICTED` except chat-style command rows (`CHAT_CONTENT`).
Session rows use the persisted `session_id` as correlationId/businessId.

| Event | Outcome(s) | Key facts |
| --- | --- | --- |
| `doublelife.staff.admin_mode.entered` | `COMMITTED` (risk `HIGH` for TURBO) | `mode`, `trigger`, `max_minutes`, `entry_commands`, `granted_nodes`, `lp_user_loaded`, `gamemode`, location, `snapshot_*` inventory summary |
| `doublelife.staff.admin_mode.exited` | `COMMITTED`; `FAILED` (`restore_failed`; `end_failed`) | `reason` (`manual`/`gui`/`expired`/`quit`/`shutdown`/`restore_retry`), `duration_s`, `activity_count`, `restore_ok`, `gamemode_before`, location. `end_failed` is sent when ending the session threw (from `/dl end`, the GUI end button, expiry and the other end paths): it carries `reason`, `error`, `player_online`, `duration_s`, `extension_min`, `activity_count`, `lp_user_loaded` and none of the restore fields; TURBO permissions are still removed and the snapshot stays pending for a retry on the next join |
| `doublelife.staff.admin_mode.risk_scored` | `OBSERVED` (risk `HIGH` for a high-risk score) | `risk_level`, `risk_score`; emitted once scoring completes, so it may arrive before or after `exited` (inline on the server thread during shutdown); correlate by `session_id` |
| `doublelife.staff.admin_mode.resumed` | `COMMITTED`; `CANCELLED` (`expired`/`skipped_active`); `FAILED` | `outcome`, `reapplied_nodes`, `elapsed_min`, `restored_activity_count`, location |
| `doublelife.staff.admin_mode.quit_during_session` | `OBSERVED` (risk `HIGH`) | `gamemode`, `minutes_remaining`, location; followed by `exited` with `reason=quit` |
| `doublelife.staff.admin_mode.extended` | `COMMITTED`; `DENIED` (`cap_exceeded`) | `added_min`, `new_total_min`, `lp_expiry_updated=false` |
| `doublelife.staff.admin_mode.action` | `ATTEMPTED`/`DENIED` (typed); `OBSERVED`/`FAILED` (entry command) | `kind=command`, `dispatcher`, `command`, `command_name`, `cancelled`, `sensitive` (risk `HIGH`); `sensitive` and the chat privacy class use the command parse the interceptor already made for the same event |
| `doublelife.staff.admin_mode.item_out` | `OBSERVED`; `ATTEMPTED` for `give_command` | `kind` (`container_put`/`container_take`/`creative_spawn`/`drop`/`drop_picked_up`/`armor_stand_put`/`give_command`), `material`, `amount` (`drop_picked_up` and `give_command`) or batch fields (every other kind), `container_type`, `owner_uuid` (container kinds only, another player's inventory or ender chest), `delivered` / `delivered_amount` |
| `doublelife.staff.admin_mode.item_in` | `OBSERVED` | `kind` (`pickup`/`armor_stand_take`), batch fields |
| `doublelife.staff.admin_mode.container_opened` | `OBSERVED` | `inventory_type`, `holder_type`, `size`, location |
| `doublelife.staff.admin_mode.teleport` | `OBSERVED` | `cause` (`COMMAND`/`PLUGIN`/`SPECTATE`), `from_*`, destination |
| `doublelife.staff.admin_mode.blocks` | `OBSERVED` | `kind` (`place`/`break`), batch fields |
| `doublelife.staff.gamemode.changed` | `OBSERVED` (risk `HIGH` to creative) | `from`, `to`, `cause`, `in_session`, `actor_type`, `source_dispatcher`, `source_command_name` (the typed label, aliases are not resolved), `source_session_id`; actor: same-tick command issuer for `COMMAND`/`PLUGIN`, else the player; empty for DoubleLife's own changes and console/RCON/command blocks |
| `doublelife.staff.permission.granted` | `COMMITTED`; `FAILED` (incl. `lp_user_not_loaded`); `ATTEMPTED` (`pending`) | `actor_type=system`, `node`, `value`, `expiry`, `lp_result` |
| `doublelife.staff.permission.revoked` | as above | `node`, `value`, `lp_result` (`cleared`) |
| `doublelife.staff.op.revoked` | `COMMITTED` (`whitelist`, the player was op); `ATTEMPTED` (`session_end`, whether the player was op is not read) | `reason` (`session_end`/`whitelist`), `was_op` (`whitelist` only), location |
| `doublelife.staff.op.blocked` | `DENIED` (`not_whitelisted`) | `target_name`, `command` |
| `doublelife.staff.command.restricted_blocked` | `DENIED` (`session_required`) | `command_name`, `command`, `group`, location |
| `doublelife.system.config.reloaded` | `COMMITTED`; `FAILED` (exception class in `reason`, `error`) | actor (player uuid, or none for console/RCON), `actor_type` (`player`/`console`), `actor_name` (sender name), new values `op_whitelist_enabled`, `temporary_permissions`, `entry_commands`, `max_minutes` (COMMITTED only) and the previous values `old_op_whitelist_enabled`, `old_temporary_permissions`, `old_entry_commands`, `old_max_minutes` (both outcomes) |

Item fields: `material` and `amount` only.
Inventory summary (`entered` only): `snapshot_stack_count`, `snapshot_item_count`, built from the
snapshot DoubleLife already holds in memory. There is no content hash and no item ids.
Item moves (except `drop_picked_up` and `give_command`), blocks and creative slot writes are batched into one row per
10 s window (`batched=true`, `materials`, `total_amount`, `event_count`, `window_*_ms`, bounding box);
a session's batches are flushed before its `exited` row, and stale batches are flushed every 5 s by an async timer.
A drag of a stack over container slots is counted from the cursor's loss, shared over the dragged slots, so the amount can
be a little off when a slot fills up.
`drop` rows are emitted at the event with `delivered=true` (cancelled events are not audited); they
are not re-checked one tick later, so a drop removed by another plugin in the same tick still shows as delivered.

Not recorded, because each needs a lookup on the server thread that only the row would use:
- Item rows: `display_name`, `item_uuid`, `parent_item_uuid` (they need the item meta and its data container) and the batch
  `display_names`. Telling a CoI-tracked item apart also needed that read, so tracked items no longer get a row of their own.
- `teleport`: `nearest_player_uuid`, `nearest_player_distance` and the row's target (they need a scan of every player in the
  destination world).
- `container_opened`: `owner_uuid`, `owner_source`, `claim_owner_uuid`, `claim_land_ulid`, `claim_viewer_trusted` and `locked`
  (they need a Lands claim lookup and a lock read). The `owner_uuid` on container item rows is no longer a claim owner: it is
  set only when the open inventory is another player's (invsee, ender chest), read from the inventory's holder. DoubleLife no
  longer depends on Lands.
- `exited` and `quit_during_session`: the live inventory summaries (`before_*`, `after_*` and the quit counts), which read every
  stack the player carried. `perms_removed` is gone too, because telling which nodes were really present needed a LuckPerms
  node scan; the per-node `permission.revoked` rows say `lp_result=cleared`.
- `action` and `give_command`: the target uuid, which needed an online-player lookup for each typed argument (`target_arg`
  keeps the typed text). A `/give` whose first argument is not the issuer's own name or `@s` is logged as a give to another player.
- `op.blocked`: the target uuid (an online and offline player lookup); `target_name` keeps the typed name.
- `gamemode.changed`: `source_command_name` is the typed label; resolving an alias needed a command map lookup. Rows are
  written for session players and players holding `doublelife.use`; a LuckPerms staff group is no longer looked up for the
  decision, so staff who hold a staff group but not `doublelife.use` are not recorded.
- `entered`: `snapshot_items` (the item list). The counts stay.
- `exited`: `deop_applied` (it read the op flag twice) and the `offline_expiry` reason (it read whether the player was online);
  both expiry cases now say `expired`. `op.revoked` for `session_end` has no `was_op`.
- Item rows: the stack put into a container by a hotbar-key click (it needs a read of the hotbar slot; the stack taken out is
  still recorded) and the `frame_put` row (it needs a read of the held item, which the event does not carry). Item stacks are
  no longer copied for the row: the material name is read at the event.
- `action` rows for a command the interceptor did not parse (it did not run for that event) match only the typed label, so an
  alias of a sensitive or chat command is not recognised.
