create UNIQUE INDEX IF NOT EXISTS old_classifier_and_type_index ON public.database USING btree (old_classifier, type) WHERE ((old_classifier ->> 'MARKED_FOR_DROP'::text) IS NULL);

DROP INDEX IF EXISTS classifier_and_type_index;
create UNIQUE INDEX IF NOT EXISTS classifier_and_type_index ON public.database USING btree (classifier, type) WHERE ((classifier ->> 'MARKED_FOR_DROP'::text) IS NULL and (classifier ->> 'V3_TRANSFORMATION'::text) IS NULL);
