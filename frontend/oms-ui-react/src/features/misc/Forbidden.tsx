import Button from '@mui/material/Button';
import { Link } from 'react-router-dom';
import { PageHeader } from '../../components/PageHeader';

export default function Forbidden() {
  return (
    <>
      <PageHeader title="Not allowed" subtitle="Your role does not include this screen." />
      <p>Navigation hides screens you cannot use, and the server enforces permissions on every request regardless.</p>
      <Button variant="contained" component={Link} to="/dashboard">
        Back to dashboard
      </Button>
    </>
  );
}
