import Button from '@mui/material/Button';
import { Link } from 'react-router-dom';
import { PageHeader } from '../../components/PageHeader';

export default function NotFound() {
  return (
    <>
      <PageHeader title="Page not found" subtitle="That address does not exist." />
      <Button variant="contained" component={Link} to="/dashboard">
        Back to dashboard
      </Button>
    </>
  );
}
