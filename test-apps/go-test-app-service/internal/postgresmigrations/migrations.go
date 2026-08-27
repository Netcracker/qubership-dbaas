package postgresmigrations

import "github.com/uptrace/bun/migrate"

const ItemsTable = "go_test_app_items"

var migrations = migrate.NewMigrations()

func Migrations() *migrate.Migrations {
	return migrations
}
