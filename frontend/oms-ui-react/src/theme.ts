import { createTheme } from '@mui/material/styles';

// System font stack only: no CDN fonts (CSP font-src 'self'). Colours chosen for >= 4.5:1 contrast on white.
const FONT = '"Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif';

export const theme = createTheme({
  palette: {
    primary: { main: '#0b3d91' },
    error: { main: '#b3261e' },
    text: { primary: '#1b1f27', secondary: '#4b5563' },
    background: { default: '#f5f7fa' },
  },
  typography: { fontFamily: FONT, button: { textTransform: 'none', fontWeight: 600 } },
  components: {
    MuiTextField: { defaultProps: { variant: 'outlined', size: 'small', margin: 'dense' } },
    MuiButton: { defaultProps: { disableElevation: true } },
    MuiAlert: { styleOverrides: { filled: { fontWeight: 500 } } },
  },
});
