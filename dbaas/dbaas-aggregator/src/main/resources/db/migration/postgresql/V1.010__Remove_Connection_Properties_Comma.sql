UPDATE public.database
set connection_properties = '[{"role": "admin"}]'
where connection_properties = '[{"role": "admin", }]';
