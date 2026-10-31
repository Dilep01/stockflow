CREATE FUNCTION reject_movement_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Stock movements are append-only; use a compensating movement';
END;
$$;
CREATE TRIGGER immutable_stock_movements BEFORE UPDATE OR DELETE ON stock_movements
FOR EACH ROW EXECUTE FUNCTION reject_movement_mutation();
