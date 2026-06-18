-- Index the classifier -> database foreign key. findChangedSince joins classifier
-- to database and orders by (database.last_rotated_at, classifier.id); without an
-- index on the join column the query falls back to a full classifier scan, which
-- is worst when many registries share one last_rotated_at (the restore case).
-- Also speeds up the FK cascade deletes from database to classifier.
create index idx_classifier_database_id
    on classifier (database_id);
