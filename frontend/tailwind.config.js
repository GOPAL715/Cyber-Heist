/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        // Cyberpunk palette used across the whole UI.
        void: '#05060f',
        night: '#0a0e1d',
        panel: '#111827',
        neon: '#00f0ff',
        magenta: '#ff2e88',
        lime: '#39ff14',
        amber: '#ffb300',
      },
      fontFamily: {
        mono: ['"JetBrains Mono"', 'ui-monospace', 'SFMono-Regular', 'monospace'],
      },
      boxShadow: {
        neon: '0 0 20px rgba(0, 240, 255, 0.35)',
        magenta: '0 0 20px rgba(255, 46, 136, 0.35)',
      },
      keyframes: {
        flicker: {
          '0%, 100%': { opacity: '1' },
          '50%': { opacity: '0.85' },
        },
      },
      animation: {
        flicker: 'flicker 3s ease-in-out infinite',
      },
    },
  },
  plugins: [],
}