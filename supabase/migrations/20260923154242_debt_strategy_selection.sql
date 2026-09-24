alter table public.debt_preferences
  add column preferred_strategy text default 'recommended'
    check (preferred_strategy in ('recommended', 'avalanche', 'snowball', 'hybrid'));
