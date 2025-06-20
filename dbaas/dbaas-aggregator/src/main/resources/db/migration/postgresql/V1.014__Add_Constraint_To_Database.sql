ALTER TABLE public.database DROP CONSTRAINT if exists correct_classifier;

ALTER TABLE public.database
    ADD CONSTRAINT correct_classifier
        check (classifier ->>'V3_TRANSFORMATION' is not null or classifier ->>'scope' is not null) NOT VALID;