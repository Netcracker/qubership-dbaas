package postgresmigrations

import (
	"context"

	"github.com/uptrace/bun"
)

func init() {
	migrations.MustRegister(func(ctx context.Context, db *bun.DB) error {
		_, err := db.ExecContext(ctx, `
CREATE TABLE IF NOT EXISTS go_test_app_items (
	id BIGSERIAL PRIMARY KEY,
	name TEXT NOT NULL,
	created_at TIMESTAMPTZ NOT NULL DEFAULT now()
)`)
		return err
	}, func(ctx context.Context, db *bun.DB) error {
		_, err := db.ExecContext(ctx, `DROP TABLE IF EXISTS go_test_app_items`)
		return err
	})
}
