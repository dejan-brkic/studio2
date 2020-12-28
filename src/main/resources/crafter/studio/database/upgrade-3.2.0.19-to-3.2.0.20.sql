/*
 * Copyright (C) 2007-2020 Crafter Software Corporation. All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as published by
 * the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

call addColumnIfNotExists('crafter', 'audit', 'commit_id', 'VARCHAR(50) NULL') ;

call addColumnIfNotExists('crafter', 'site', 'last_synced_gitlog_commit_id', 'VARCHAR(50) NULL') ;

call addColumnIfNotExists('crafter', 'gitlog', 'audited', 'INT NOT NULL DEFAULT 0') ;

call addColumnIfNotExists('crafter', 'cluster_site_sync_repo', 'node_last_synced_gitlog_commit_id', 'VARCHAR(50) NULL DEFAULT 1') ;

UPDATE _meta SET version = '3.2.0.20' ;