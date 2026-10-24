import MenuItem from '@mui/material/MenuItem';
import TextField from '@mui/material/TextField';
import { useState } from 'react';
import { DataTable, type Column } from '../../components/DataTable';
import { PageHeader } from '../../components/PageHeader';
import { useListQuery } from '../../hooks/listQuery';
import { useDebouncedValue } from '../../hooks/useDebouncedValue';
import { SERVICES, type AuditEvent, type ServiceName } from '../../models';
import { useListAuditQuery } from '../../store/api';

const columns: Column<AuditEvent>[] = [
  { key: 'at', header: 'When', value: (a) => a.occurredAt, type: 'date' },
  { key: 'type', header: 'Entity', value: (a) => a.entityType },
  { key: 'id', header: 'Entity ID', value: (a) => a.entityId },
  { key: 'action', header: 'Action', value: (a) => a.action },
  { key: 'actor', header: 'Actor', value: (a) => a.actor ?? 'system' },
  { key: 'details', header: 'Details', value: (a) => a.details ?? '' },
];

export default function Audit() {
  const [service, setService] = useState<ServiceName>('order');
  const [entityType, setEntityType] = useState('');
  const [entityId, setEntityId] = useState('');
  const filters = useDebouncedValue({ service, entityType: entityType.trim(), entityId: entityId.trim() }, 300);
  const list = useListQuery(filters, 20);
  const { query } = list;
  const res = useListAuditQuery({ svc: query.filters.service, entityType: query.filters.entityType, entityId: query.filters.entityId, page: query.page, size: query.size });

  return (
    <>
      <PageHeader title="Audit history" subtitle="Who changed what, per service." />
      <form className="filters" role="search" aria-label="Filter audit events" onSubmit={(e) => e.preventDefault()}>
        <TextField
          select
          label="Service"
          value={service}
          onChange={(e) => setService(e.target.value as ServiceName)}
          slotProps={{ select: { SelectDisplayProps: { 'data-testid': 'audit-service' } as React.HTMLAttributes<HTMLDivElement> } }}
        >
          {SERVICES.map((s) => (
            <MenuItem key={s} value={s}>
              {s}
            </MenuItem>
          ))}
        </TextField>
        <TextField label="Entity type" placeholder="e.g. Order, Product" value={entityType} onChange={(e) => setEntityType(e.target.value)} />
        <TextField className="grow" label="Entity ID" value={entityId} onChange={(e) => setEntityId(e.target.value)} />
      </form>
      <DataTable
        caption="Audit events"
        columns={columns}
        rows={res.data?.content ?? []}
        rowKey={(a) => a.id}
        loading={res.isFetching}
        error={res.error ?? null}
        total={res.data?.totalElements ?? 0}
        page={query.page}
        size={query.size}
        emptyTitle="No audit events"
        onPageChange={list.setPage}
        onRetry={() => void res.refetch()}
      />
    </>
  );
}
